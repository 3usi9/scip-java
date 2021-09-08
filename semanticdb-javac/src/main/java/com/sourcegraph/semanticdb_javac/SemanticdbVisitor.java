package com.sourcegraph.semanticdb_javac;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Position;
import com.sourcegraph.semanticdb_javac.Semanticdb.SymbolInformation.Kind;
import com.sourcegraph.semanticdb_javac.Semanticdb.SymbolInformation.Property;
import com.sourcegraph.semanticdb_javac.Semanticdb.SymbolOccurrence.Role;

import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import static com.sourcegraph.semanticdb_javac.SemanticdbBuilders.*;

/** Walks the AST of a typechecked compilation unit and generates a SemanticDB TextDocument. */
public class SemanticdbVisitor extends TreePathScanner<Void, Void> {

  private final GlobalSymbolsCache globals;
  private final LocalSymbolsCache locals;
  private final JavacTask task;
  private final TaskEvent event;
  private final Types javacTypes;
  private final Trees trees;
  private final SemanticdbJavacOptions options;
  private final ArrayList<Semanticdb.SymbolOccurrence> occurrences;
  private final ArrayList<Semanticdb.SymbolInformation> symbolInfos;
  private String source;

  public SemanticdbVisitor(
      JavacTask task,
      GlobalSymbolsCache globals,
      TaskEvent event,
      SemanticdbJavacOptions options,
      Types javacTypes) {
    this.task = task;
    this.globals = globals; // Reused cache between compilation units.
    this.locals = new LocalSymbolsCache(); // Fresh cache per compilation unit.
    this.event = event;
    this.options = options;
    this.javacTypes = javacTypes;
    this.trees = Trees.instance(task);
    this.occurrences = new ArrayList<>();
    this.symbolInfos = new ArrayList<>();
    this.source = semanticdbText();
  }

  public Semanticdb.TextDocument buildTextDocument(CompilationUnitTree tree) {
    this.scan(tree, null); // Trigger recursive AST traversal to collect SemanticDB information.

    return Semanticdb.TextDocument.newBuilder()
        .setSchema(Semanticdb.Schema.SEMANTICDB4)
        .setLanguage(Semanticdb.Language.JAVA)
        .setUri(semanticdbUri())
        .setText(options.includeText ? this.source : "")
        .setMd5(semanticdbMd5())
        .addAllOccurrences(occurrences)
        .addAllSymbols(symbolInfos)
        .build();
  }

  private void emitSymbolOccurrence(Element sym, Tree posTree, Role role, CompilerRange kind) {
    if (sym == null) return;
    Optional<Semanticdb.SymbolOccurrence> occ = semanticdbOccurrence(sym, posTree, kind, role);
    occ.ifPresent(occurrences::add);
    if (role == Role.DEFINITION) {
      // Only emit SymbolInformation for symbols that are defined in this compilation unit.
      emitSymbolInformation(sym, posTree);
    }
  }

  private void emitSymbolInformation(Element sym, Tree tree) {
    Semanticdb.SymbolInformation.Builder builder = symbolInformation(semanticdbSymbol(sym));
    Semanticdb.Documentation documentation = semanticdbDocumentation(sym);
    if (documentation != null) builder.setDocumentation(documentation);
    Semanticdb.Signature signature = semanticdbSignature(sym);
    if (signature != null) builder.setSignature(signature);
    List<Semanticdb.AnnotationTree> annotations =
        new SemanticdbTrees(
                globals, locals, semanticdbUri(), trees, javacTypes, event.getCompilationUnit())
            .annotations(tree);
    if (annotations != null) builder.addAllAnnotations(annotations);

    builder
        .setProperties(semanticdbSymbolInfoProperties(sym))
        .setDisplayName(sym.getSimpleName().toString())
        .setAccess(semanticdbAccess(sym));

    switch (sym.getKind()) {
      case ENUM:
      case CLASS:
        builder.setKind(Kind.CLASS);
        break;
      case INTERFACE:
      case ANNOTATION_TYPE:
        builder.setKind(Kind.INTERFACE);
        break;
      case FIELD:
        builder.setKind(Kind.FIELD);
        break;
      case METHOD:
        builder.setKind(Kind.METHOD);
        //        builder.addAllOverriddenSymbols(semanticdbOverrides(sym));
        break;
      case CONSTRUCTOR:
        builder.setKind(Kind.CONSTRUCTOR);
        break;
      case TYPE_PARAMETER:
        builder.setKind(Kind.TYPE_PARAMETER);
        break;
      case ENUM_CONSTANT: // overwrite previous value here
        String args =
            ((NewClassTree) ((VariableTree) tree).getInitializer())
                .getArguments().stream().map(Tree::toString).collect(Collectors.joining(", "));
        if (!args.isEmpty())
          builder.setDisplayName(sym.getSimpleName().toString() + "(" + args + ")");
    }

    Semanticdb.SymbolInformation info = builder.build();

    symbolInfos.add(info);
  }

  // =======================================
  // Overridden methods from TreePathScanner
  // =======================================

  @Override
  public Void visitClass(ClassTree node, Void unused) {
    Element element = trees.getElement(getCurrentPath());
    if (element == null) return super.visitClass(node, unused);

    emitSymbolOccurrence(element, node, Role.DEFINITION, CompilerRange.FROM_POINT_WITH_TEXT_SEARCH);

    for (TypeParameterTree typeParameter : node.getTypeParameters()) {
      Element typeParamElement =
          trees.getElement(trees.getPath(event.getCompilationUnit(), typeParameter));
      emitSymbolOccurrence(
          typeParamElement,
          typeParameter,
          Role.DEFINITION,
          CompilerRange.FROM_POINT_TO_SYMBOL_NAME);
    }

    return super.visitClass(node, unused);
  }

  @Override
  public Void visitMethod(MethodTree node, Void unused) {
    ExecutableElement element = (ExecutableElement) trees.getElement(getCurrentPath());
    if (element == null) return super.visitMethod(node, unused);

    CompilerRange range = CompilerRange.FROM_POINT_TO_SYMBOL_NAME;
    if (element.getSimpleName().contentEquals("<init>")) {
      if (element.getEnclosingElement().getSimpleName().toString().isEmpty()) return null;
      range = CompilerRange.FROM_POINT_WITH_TEXT_SEARCH;
    }

    emitSymbolOccurrence(element, node, Role.DEFINITION, range);

    for (TypeParameterTree typeParameter : node.getTypeParameters()) {
      Element typeParamElement =
          trees.getElement(trees.getPath(event.getCompilationUnit(), typeParameter));
      emitSymbolOccurrence(
          typeParamElement,
          typeParameter,
          Role.DEFINITION,
          CompilerRange.FROM_POINT_TO_SYMBOL_NAME);
    }

    return super.visitMethod(node, unused);
  }

  @Override
  public Void visitVariable(VariableTree node, Void unused) {
    Element element = trees.getElement(getCurrentPath());
    if (element == null) return super.visitVariable(node, unused);

    emitSymbolOccurrence(element, node, Role.DEFINITION, CompilerRange.FROM_POINT_TO_SYMBOL_NAME);

    return super.visitVariable(node, unused);
  }

  @Override
  public Void visitIdentifier(IdentifierTree node, Void unused) {
    Element element = trees.getElement(getCurrentPath());
    if (element == null || node.getName() == null) return null;
    if (node.getName().contentEquals("this") && element.getKind() != ElementKind.CONSTRUCTOR)
      return null;

    emitSymbolOccurrence(element, node, Role.REFERENCE, CompilerRange.FROM_START_TO_END);

    return super.visitIdentifier(node, unused);
  }

  @Override
  public Void visitMemberReference(MemberReferenceTree node, Void unused) {
    Element element = trees.getElement(getCurrentPath());
    if (element == null) return super.visitMemberReference(node, unused);

    emitSymbolOccurrence(element, node, Role.REFERENCE, CompilerRange.FROM_END_WITH_TEXT_SEARCH);

    return super.visitMemberReference(node, unused);
  }

  @Override
  public Void visitMemberSelect(MemberSelectTree node, Void unused) {
    Element element = trees.getElement(getCurrentPath());
    if (element == null) return super.visitMemberSelect(node, unused);

    emitSymbolOccurrence(
        element, node, Role.REFERENCE, CompilerRange.FROM_POINT_TO_SYMBOL_NAME_PLUS_ONE);

    return super.visitMemberSelect(node, unused);
  }

  @Override
  public Void visitNewClass(NewClassTree node, Void unused) {
    Element identElement =
        trees.getElement(trees.getPath(event.getCompilationUnit(), node.getIdentifier()));
    Element ctorElement = trees.getElement(getCurrentPath());
    if (identElement != null
        && identElement.asType() != null
        && !ctorElement.getEnclosingElement().getSimpleName().toString().isEmpty()) {
      emitSymbolOccurrence(
          ctorElement, node.getIdentifier(), Role.REFERENCE, CompilerRange.FROM_TEXT_SEARCH);
    }

    // to avoid emitting a reference to the class itself, we manually scan everything
    // except the identifier
    scan(node.getTypeArguments(), unused);
    scan(node.getArguments(), unused);
    return scan(node.getClassBody(), unused);
  }

  // =================================================
  // Utilities to generate SemanticDB data structures.
  // =================================================

  private Semanticdb.Signature semanticdbSignature(Element sym) {

    return new SemanticdbSignatures(globals, locals, javacTypes).generateSignature(sym);
  }

  private String semanticdbSymbol(Element sym) {
    return globals.semanticdbSymbol(sym, locals);
  }

  private Optional<Semanticdb.Range> semanticdbRange(Tree pos1, CompilerRange kind, Element sym) {
    LineMap lineMap = event.getCompilationUnit().getLineMap();
    int start, end;
    JCDiagnostic.DiagnosticPosition pos = (JCDiagnostic.DiagnosticPosition) pos1;
    // if (kind.isFromPoint() && sym.getSimpleName() != null) {
    //   start = (int) trees.getSourcePositions().getStartPosition(event.getCompilationUnit(), pos);
    //   if (kind == CompilerRange.FROM_POINT_TO_SYMBOL_NAME_PLUS_ONE) {
    //     start++;
    //   }
    //   end = start + sym.getSimpleName().length();
    // } else {
    //   start = (int) trees.getSourcePositions().getStartPosition(event.getCompilationUnit(), pos);
    //   end = (int) trees.getSourcePositions().getEndPosition(event.getCompilationUnit(), pos);
    // }
    if (kind.isFromPoint() && sym.getSimpleName() != null) {
      start = pos.getPreferredPosition();
      if (kind == CompilerRange.FROM_POINT_TO_SYMBOL_NAME_PLUS_ONE) {
        start++;
      }
      end = start + sym.getSimpleName().length();
    } else {
      start = pos.getStartPosition();
      end = (int) trees.getSourcePositions().getEndPosition(event.getCompilationUnit(), (Tree) pos);
    }

    if (kind.isFromTextSearch() && sym.getSimpleName().length() > 0) {
      Optional<Semanticdb.Range> range =
          RangeFinder.findRange(
              getCurrentPath(),
              trees,
              getCurrentPath().getCompilationUnit(),
              sym,
              start,
              this.source,
              kind.isFromEnd());
      if (range.isPresent()) return Optional.of(correctForTabs(range.get(), lineMap, start));
      else return range;
    } else if (start != Position.NOPOS && end != Position.NOPOS && end > start) {
      Semanticdb.Range range =
          Semanticdb.Range.newBuilder()
              .setStartLine((int) lineMap.getLineNumber(start) - 1)
              .setStartCharacter((int) lineMap.getColumnNumber(start) - 1)
              .setEndLine((int) lineMap.getLineNumber(end) - 1)
              .setEndCharacter((int) lineMap.getColumnNumber(end) - 1)
              .build();

      range = correctForTabs(range, lineMap, start);

      return Optional.of(range);
    }

    return Optional.empty();
  }

  private Semanticdb.Range correctForTabs(Semanticdb.Range range, LineMap lineMap, int start) {
    int startLinePos = (int) lineMap.getPosition(lineMap.getLineNumber(start), 0);

    // javac replaces every tab with 8 spaces in the linemap. As this is potentially inconsistent
    // with the source file itself, we adjust for that here if the line is actually indented with
    // tabs.
    // As for every tab there are 8 spaces, we remove 7 spaces for every tab to get the correct
    // char offset (note: different to _column_ offset your editor shows)
    if (this.source.charAt(startLinePos) == '\t') {
      int count = 1;
      while (this.source.charAt(++startLinePos) == '\t') count++;
      range =
          range
              .toBuilder()
              .setStartCharacter(range.getStartCharacter() - (count * 7))
              .setEndCharacter(range.getEndCharacter() - (count * 7))
              .build();
    }

    return range;
  }

  private Optional<Semanticdb.SymbolOccurrence> semanticdbOccurrence(
      Element sym, Tree pos, CompilerRange kind, Role role) {
    Optional<Semanticdb.Range> range = semanticdbRange(pos, kind, sym);
    if (range.isPresent()) {
      String ssym = semanticdbSymbol(sym);
      if (!ssym.equals(SemanticdbSymbols.NONE)) {
        Semanticdb.SymbolOccurrence occ = symbolOccurrence(ssym, range.get(), role);
        return Optional.of(occ);
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }
  }

  private String semanticdbText() {
    if (source != null) return source;
    try {
      source = event.getSourceFile().getCharContent(true).toString();
    } catch (IOException e) {
      source = "";
    }
    return source;
  }

  private String semanticdbMd5() {
    try {
      return MD5.digest(event.getSourceFile().getCharContent(true).toString());
    } catch (IOException | NoSuchAlgorithmException e) {
      return "";
    }
  }

  private int semanticdbSymbolInfoProperties(Element sym) {
    int properties = 0;
    properties |=
        (sym.getKind() == ElementKind.ENUM || sym.getKind() == ElementKind.ENUM_CONSTANT)
            ? Property.ENUM_VALUE
            : 0;
    properties |= sym.getModifiers().contains(Modifier.STATIC) ? Property.STATIC_VALUE : 0;
    // for default interface methods, Flags.ABSTRACT is also set...
    boolean abstractNotDefault =
        sym.getModifiers().contains(Modifier.ABSTRACT)
            && !sym.getModifiers().contains(Modifier.DEFAULT);
    properties |= abstractNotDefault ? Property.ABSTRACT_VALUE : 0;
    properties |= sym.getModifiers().contains(Modifier.FINAL) ? Property.FINAL_VALUE : 0;
    properties |= sym.getModifiers().contains(Modifier.DEFAULT) ? Property.DEFAULT_VALUE : 0;
    return properties;
  }

  /*private List<String> semanticdbOverrides(Element sym) {
    ArrayList<String> overriddenSymbols = new ArrayList<>();
    Set<ExecutableElement> overriddenMethods =
        javacTypes.getOverriddenMethods(sym).stream()
            .map(m -> (ExecutableElement) m)
            .collect(Collectors.toSet());

    for (ExecutableElement meth : overriddenMethods) {
      overriddenSymbols.add(semanticdbSymbol(meth));
    }

    return overriddenSymbols;
  }*/

  private Semanticdb.Access semanticdbAccess(Element sym) {
    if (sym.getModifiers().contains(Modifier.PRIVATE)) {
      return privateAccess();
    } else if (sym.getModifiers().contains(Modifier.PUBLIC)) {
      return publicAccess();
    } else if (sym.getModifiers().contains(Modifier.PROTECTED)) {
      return protectedAccess();
    } else {
      return privateWithinAccess(semanticdbSymbol(sym.getEnclosingElement()));
    }
  }

  private String semanticdbUri() {
    Path absolutePath =
        SemanticdbTaskListener.absolutePathFromUri(options, event.getSourceFile().toUri());
    Path relativePath = options.sourceroot.relativize(absolutePath);
    StringBuilder out = new StringBuilder();
    Iterator<Path> it = relativePath.iterator();
    if (it.hasNext()) out.append(it.next().getFileName().toString());
    while (it.hasNext()) {
      Path part = it.next();
      out.append('/').append(part.getFileName().toString());
    }
    return out.toString();
  }

  private Semanticdb.Documentation semanticdbDocumentation(Element sym) {
    try {
      Elements elements = task.getElements();
      if (elements == null) return null;

      String doc = elements.getDocComment(sym);
      if (doc == null) return null;

      return Semanticdb.Documentation.newBuilder()
          .setFormat(Semanticdb.Documentation.Format.JAVADOC)
          .setMessage(doc)
          .build();
    } catch (NullPointerException e) {
      // Can happen in `getDocComment()`
      // Caused by: java.lang.NullPointerException
      //   at com.sun.tools.javac.model.JavacElements.cast(JavacElements.java:605)
      //   at com.sun.tools.javac.model.JavacElements.getTreeAndTopLevel(JavacElements.java:543)
      //   at com.sun.tools.javac.model.JavacElements.getDocComment(JavacElements.java:321)
      //   at
      // com.sourcegraph.semanticdb_javac.SemanticdbVisitor.semanticdbDocumentation(SemanticdbVisitor.java:233)
      return null;
    }
  }
}
