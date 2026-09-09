package erd.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import erd.controller.dto.ControllerInfo;
import erd.controller.dto.EndpointInfo;
import erd.controller.dto.SourceScanResponse;
import erd.service.model.ComponentLayer;
import erd.service.model.JavaComponent;
import erd.service.model.JavaMethod;
import erd.service.model.SourceModel;

@Service
public class SourceCodeAnalyzer {

	private final JavaParser javaParser;

	public SourceCodeAnalyzer() {
		ParserConfiguration configuration = new ParserConfiguration()
				.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
		this.javaParser = new JavaParser(configuration);
	}

	private static final int MAX_JAVA_FILES = 5000;
	private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");
	private static final Set<String> SERVICE_ANNOTATIONS = Set.of("Service");
	private static final Set<String> COMPONENT_ANNOTATIONS = Set.of("Component");
	private static final Set<String> REPOSITORY_ANNOTATIONS = Set.of("Repository");
	private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
			"RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

	public SourceScanResponse scanControllers(String sourcePath) {
		SourceModel model = analyze(sourcePath);
		List<ControllerInfo> controllers = model.components().stream()
				.filter(component -> component.layer() == ComponentLayer.CONTROLLER)
				.sorted(Comparator.comparing(JavaComponent::qualifiedName))
				.map(component -> new ControllerInfo(
						component.qualifiedName(),
						component.simpleName(),
						component.file().toString(),
						component.methods().stream()
								.filter(method -> !method.httpMethods().isEmpty() || !method.paths().isEmpty())
								.map(method -> new EndpointInfo(method.name(), method.httpMethods(), method.paths()))
								.toList()))
				.toList();

		if (controllers.isEmpty()) {
			List<String> warnings = new ArrayList<>(model.warnings());
			warnings.add("@Controller または @RestController が付いたクラスが見つかりませんでした。");
			return new SourceScanResponse(controllers, warnings.stream().distinct().toList());
		}
		return new SourceScanResponse(controllers, model.warnings());
	}

	@SuppressWarnings("unchecked")
	public SourceModel analyze(String sourcePath) {
		Path root = validateRoot(sourcePath);
		List<Path> files = findJavaFiles(root);
		if (files.isEmpty()) {
			throw new IllegalArgumentException("指定したパス配下に Java ソース (*.java) が見つかりませんでした。");
		}

		List<JavaComponent> components = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		for (Path file : files) {
			try {
				ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
				if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
					String problems = parseResult.getProblems().stream()
							.map(Object::toString)
							.reduce((a, b) -> a + "; " + b)
							.orElse("unknown parse error");
					warnings.add("Javaソースを解析できませんでした: " + file + " (" + problems + ")");
					continue;
				}

				CompilationUnit cu = parseResult.getResult().orElseThrow();
				String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

				for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
					if (type.findAncestor(ClassOrInterfaceDeclaration.class).isPresent()) {
						continue; // inner class is outside the target Spring layer model
					}

					ComponentLayer layer = detectLayer(type.getAnnotations());
					if (layer == null) {
						continue;
					}

					components.add(toComponent(packageName, file, type, layer));
				}
			} catch (IOException | ParseProblemException e) {
				warnings.add("Javaソースを解析できませんでした: " + file + " (" + firstLine(e.getMessage()) + ")");
			}
		}

		Map<String, JavaComponent> byQualifiedName = new LinkedHashMap<>();
		Map<String, List<JavaComponent>> byTypeName = new HashMap<>();
		for (JavaComponent component : components) {
			byQualifiedName.put(component.qualifiedName(), component);
			registerTypeName(byTypeName, component.simpleName(), component);
			registerTypeName(byTypeName, component.qualifiedName(), component);
			for (String alias : component.typeAliases()) {
				registerTypeName(byTypeName, alias, component);
			}
		}
		return new SourceModel(List.copyOf(components), byQualifiedName, byTypeName,
				warnings.stream().distinct().toList());
	}

	private JavaComponent toComponent(String packageName, Path file, ClassOrInterfaceDeclaration type,
			ComponentLayer layer) {
		Map<String, String> dependencies = new LinkedHashMap<>();
		for (FieldDeclaration field : type.getFields()) {
			for (VariableDeclarator variable : field.getVariables()) {
				dependencies.put(variable.getNameAsString(), simpleType(variable.getTypeAsString()));
			}
		}
		for (ConstructorDeclaration constructor : type.getConstructors()) {
			for (Parameter parameter : constructor.getParameters()) {
				dependencies.putIfAbsent(parameter.getNameAsString(), simpleType(parameter.getTypeAsString()));
			}
		}

		List<String> aliases = new ArrayList<>();
		for (ClassOrInterfaceType implemented : type.getImplementedTypes()) {
			aliases.add(simpleType(implemented.asString()));
		}
		if (type.isInterface()) {
			aliases.add(type.getNameAsString());
		}

		List<String> classPaths = extractPaths(type.getAnnotations());
		List<JavaMethod> methods = new ArrayList<>();
		for (MethodDeclaration method : type.getMethods()) {
			Map<String, String> vars = new LinkedHashMap<>(dependencies);
			for (Parameter parameter : method.getParameters()) {
				vars.put(parameter.getNameAsString(), simpleType(parameter.getTypeAsString()));
			}
			for (VariableDeclarator local : method.findAll(VariableDeclarator.class)) {
				vars.put(local.getNameAsString(), simpleType(local.getTypeAsString()));
			}
			methods.add(new JavaMethod(
					method.getNameAsString(),
					extractJavadocSummary(method),
					method,
					Map.copyOf(vars),
					extractHttpMethods(method.getAnnotations()),
					combinePaths(classPaths, extractPaths(method.getAnnotations()))));
		}

		String simpleName = type.getNameAsString();
		String qualified = StringUtils.isBlank(packageName) ? simpleName : packageName + "." + simpleName;
		return new JavaComponent(packageName, simpleName, qualified, layer, file.toAbsolutePath().normalize(),
				Map.copyOf(dependencies), List.copyOf(aliases), List.copyOf(methods), List.copyOf(classPaths));
	}

	private String extractJavadocSummary(MethodDeclaration method) {
		return method.getJavadocComment()
				.map(comment -> comment.parse().getDescription().toText())
				.map(text -> text.replace("\r", "\n"))
				.map(text -> text.lines()
						.map(String::trim)
						.filter(line -> !line.isEmpty())
						.findFirst()
						.orElse(""))
				.orElse("");
	}

	private ComponentLayer detectLayer(NodeList<AnnotationExpr> annotations) {
		for (AnnotationExpr annotation : annotations) {
			String name = simpleAnnotationName(annotation);
			if (CONTROLLER_ANNOTATIONS.contains(name)) {
				return ComponentLayer.CONTROLLER;
			}
			if (SERVICE_ANNOTATIONS.contains(name)) {
				return ComponentLayer.SERVICE;
			}
			if (COMPONENT_ANNOTATIONS.contains(name)) {
				return ComponentLayer.COMPONENT;
			}
			if (REPOSITORY_ANNOTATIONS.contains(name)) {
				return ComponentLayer.REPOSITORY;
			}
		}
		return null;
	}

	private List<String> extractHttpMethods(NodeList<AnnotationExpr> annotations) {
		LinkedHashSet<String> methods = new LinkedHashSet<>();
		for (AnnotationExpr annotation : annotations) {
			String name = simpleAnnotationName(annotation);
			switch (name) {
			case "GetMapping" -> methods.add("GET");
			case "PostMapping" -> methods.add("POST");
			case "PutMapping" -> methods.add("PUT");
			case "DeleteMapping" -> methods.add("DELETE");
			case "PatchMapping" -> methods.add("PATCH");
			case "RequestMapping" -> methods.addAll(extractRequestMethods(annotation));
			default -> {
			}
			}
		}
		return List.copyOf(methods);
	}

	private List<String> extractRequestMethods(AnnotationExpr annotation) {
		String text = annotation.toString();
		List<String> result = new ArrayList<>();
		for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")) {
			if (text.contains("RequestMethod." + method)) {
				result.add(method);
			}
		}
		return result;
	}

	private List<String> extractPaths(NodeList<AnnotationExpr> annotations) {
		LinkedHashSet<String> paths = new LinkedHashSet<>();
		for (AnnotationExpr annotation : annotations) {
			if (!MAPPING_ANNOTATIONS.contains(simpleAnnotationName(annotation))) {
				continue;
			}

			if (annotation instanceof SingleMemberAnnotationExpr single) {
				paths.addAll(extractStringValues(single.getMemberValue()));
			} else if (annotation instanceof NormalAnnotationExpr normal) {
				for (MemberValuePair pair : normal.getPairs()) {
					if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
						paths.addAll(extractStringValues(pair.getValue()));
					}
				}
			}
		}
		return List.copyOf(paths);
	}

	private List<String> extractStringValues(Expression expression) {
		if (expression instanceof StringLiteralExpr literal) {
			return List.of(literal.asString());
		}

		if (expression instanceof ArrayInitializerExpr array) {
			return array.getValues().stream()
					.filter(StringLiteralExpr.class::isInstance)
					.map(StringLiteralExpr.class::cast)
					.map(StringLiteralExpr::asString)
					.toList();
		}
		return List.of();
	}

	private List<String> combinePaths(List<String> classPaths, List<String> methodPaths) {
		if (classPaths.isEmpty() && methodPaths.isEmpty()) {
			return List.of();
		}
		if (classPaths.isEmpty()) {
			return methodPaths.stream().map(this::normalizeWebPath).toList();
		}
		if (methodPaths.isEmpty()) {
			return classPaths.stream().map(this::normalizeWebPath).toList();
		}

		List<String> result = new ArrayList<>();
		for (String classPath : classPaths) {
			for (String methodPath : methodPaths) {
				result.add(normalizeWebPath(classPath + "/" + methodPath));
			}
		}
		return result.stream().distinct().toList();
	}

	private String normalizeWebPath(String path) {
		if (StringUtils.isBlank(path)) {
			return "/";
		}

		String normalized = path.replaceAll("/{2,}", "/");
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}

		if (1 < normalized.length() && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private String simpleAnnotationName(AnnotationExpr annotation) {
		String name = annotation.getNameAsString();
		int dot = name.lastIndexOf('.');
		if (0 <= dot) {
			return name.substring(dot + 1);
		}
		return name;
	}

	private Path validateRoot(String sourcePath) {
		if (StringUtils.isBlank(sourcePath)) {
			throw new IllegalArgumentException("Javaソースのパスを入力してください。");
		}

		Path root;
		try {
			root = Paths.get(sourcePath).toAbsolutePath().normalize();
		} catch (Exception e) {
			throw new IllegalArgumentException("Javaソースのパスが不正です。");
		}

		if (!Files.exists(root)) {
			throw new IllegalArgumentException("指定したパスが存在しません: " + root);
		}

		if (!Files.isDirectory(root)) {
			throw new IllegalArgumentException("ディレクトリを指定してください: " + root);
		}

		if (!Files.isReadable(root)) {
			throw new IllegalArgumentException("指定したパスを読み取れません: " + root);
		}
		return root;
	}

	private List<Path> findJavaFiles(Path root) {
		try (Stream<Path> stream = Files.walk(root)) {
			List<Path> files = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> !containsBuildDirectory(root, path))
					.limit(MAX_JAVA_FILES + 1L)
					.sorted()
					.toList();
			if (files.size() > MAX_JAVA_FILES) {
				throw new IllegalArgumentException("Javaソースが多すぎます。" + MAX_JAVA_FILES + " ファイル以下のパスを指定してください。");
			}
			return files;
		} catch (IOException e) {
			throw new IllegalArgumentException("Javaソースを走査できませんでした: " + e.getMessage(), e);
		}
	}

	private boolean containsBuildDirectory(Path root, Path file) {
		Path relative = root.relativize(file);
		for (Path part : relative) {
			String name = part.toString().toLowerCase(Locale.ROOT);
			if (name.equals("build") || name.equals("target") || name.equals(".gradle") || name.equals(".git")) {
				return true;
			}
		}
		return false;
	}

	private String simpleType(String type) {
		String value = type.replaceAll("<.*>", "").replace("[]", "").trim();
		int dot = value.lastIndexOf('.');
		if (0 <= dot) {
			return value.substring(dot + 1);
		}
		return value;
	}

	private void registerTypeName(Map<String, List<JavaComponent>> map, String name, JavaComponent component) {
		if (StringUtils.isBlank(name)) {
			return;
		}
		map.computeIfAbsent(name, ignored -> new ArrayList<>()).add(component);
	}

	private String firstLine(String text) {
		if (StringUtils.isBlank(text)) {
			return "unknown error";
		}
		int lf = text.indexOf('\n');
		if (0 <= lf) {
			return text.substring(0, lf);
		}
		return text;
	}
}
