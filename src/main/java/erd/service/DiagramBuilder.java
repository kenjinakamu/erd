package erd.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.github.javaparser.Position;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import erd.service.model.ComponentLayer;
import erd.service.model.JavaComponent;
import erd.service.model.JavaMethod;
import erd.service.model.SourceModel;

final class DiagramBuilder {

	private static final int MAX_DEPTH = 12;
	private static final int MAX_CALLS = 250;

	private final JavaComponent controller;
	private final SourceModel model;
	private final List<String> warnings;
	private final Map<String, JavaComponent> participants = new LinkedHashMap<>();
	private final List<String> flowLines = new ArrayList<>();
	private final Set<String> excludedClasses;
	private int callCount;

	DiagramBuilder(JavaComponent controller, SourceModel model, List<String> warnings, Set<String> excludedClasses) {
		this.controller = controller;
		this.model = model;
		this.warnings = warnings;
		this.excludedClasses = excludedClasses == null ? Set.of() : excludedClasses;
		participants.put(controller.qualifiedName(), controller);
	}

	void appendEndpoint(JavaMethod method) {
		flowLines.add("");
		flowLines.add("    Client->>+" + alias(controller) + ": " + escape(methodDisplayName(method)));
		walk(controller, method, 0, new HashSet<>());
		flowLines.add("    " + alias(controller) + "-->>-Client: response");
	}

	void walk(JavaComponent caller, JavaMethod method, int depth, Set<String> stack) {
		if (depth >= MAX_DEPTH || callCount >= MAX_CALLS) {
			warnings.add("呼び出し追跡が上限に達したため、一部を省略しました。");
			return;
		}
		String key = caller.qualifiedName() + "#" + method.name() + "/" + method.declaration().getParameters().size();
		if (!stack.add(key)) {
			flowLines.add("    Note over " + alias(caller) + ": recursive call omitted: " + escape(method.name()));
			return;
		}

		List<MethodCallExpr> calls = method.declaration().findAll(MethodCallExpr.class).stream()
				.filter(call -> nearestMethod(call).map(method.declaration()::equals).orElse(false))
				.sorted(Comparator.comparing(
						call -> call.getBegin().orElse(new Position(Integer.MAX_VALUE, Integer.MAX_VALUE))))
				.toList();

		for (MethodCallExpr call : calls) {
			if (callCount >= MAX_CALLS) {
				break;
			}

			Optional<JavaMethod> internalMethod = resolveInternalMethod(caller, call);
			if (internalMethod.isPresent()) {
				walk(caller, internalMethod.get(), depth + 1, new HashSet<>(stack));
				continue;
			}

			Target target = resolveTarget(caller, method, call);
			if (target == null || target.component().qualifiedName().equals(caller.qualifiedName())) {
				continue;
			}
			if (excludedClasses.contains(target.component().simpleName())) {
				continue;
			}
			if (!allowedLayerTransition(caller.layer(), target.component().layer())) {
				continue;
			}

			callCount++;
			participants.put(target.component().qualifiedName(), target.component());
			String callerAlias = alias(caller);
			String targetAlias = alias(target.component());
			Optional<JavaMethod> targetMethod = resolveMethod(target.component(), call);
			String messageLabel = targetMethod
					.map(this::methodDisplayName)
					.orElseGet(() -> callLabel(call));
			flowLines.add("    " + callerAlias + "->>+" + targetAlias + ": " + escape(messageLabel));
			targetMethod.ifPresent(value -> walk(target.component(), value, depth + 1, new HashSet<>(stack)));
			flowLines.add("    " + targetAlias + "-->>-" + callerAlias + ": return");
		}
	}

	private Optional<JavaMethod> resolveInternalMethod(JavaComponent caller, MethodCallExpr call) {
		boolean internalScope = call.getScope().isEmpty()
				|| call.getScope().filter(ThisExpr.class::isInstance).isPresent();
		if (!internalScope) {
			return Optional.empty();
		}
		return resolveMethod(caller, call);
	}

	private Optional<JavaMethod> resolveMethod(JavaComponent component, MethodCallExpr call) {
		List<JavaMethod> sameName = component.methods().stream()
				.filter(candidate -> candidate.name().equals(call.getNameAsString()))
				.toList();
		if (sameName.isEmpty()) {
			return Optional.empty();
		}

		int argumentCount = call.getArguments().size();
		return sameName.stream()
				.filter(candidate -> candidate.declaration().getParameters().size() == argumentCount)
				.findFirst()
				.or(() -> sameName.stream().findFirst());
	}

	private Target resolveTarget(JavaComponent caller, JavaMethod method, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return null;
		}

		String variable = variableName(call.getScope().get());
		if (variable == null) {
			return null;
		}

		String type = method.variableTypes().get(variable);
		if (type == null) {
			type = caller.dependencyVariables().get(variable);
		}
		if (type == null) {
			return null;
		}

		List<JavaComponent> candidates = model.componentsByTypeName().getOrDefault(type, List.of());
		if (candidates.isEmpty()) {
			return null;
		}

		List<JavaComponent> exactCandidates = exactTypeNameCandidates(type, candidates);
		List<JavaComponent> selectionPool = exactCandidates.isEmpty() ? candidates : exactCandidates;
		JavaComponent selected = selectCandidate(caller, selectionPool);
		if (exactCandidates.size() > 1) {
			warnings.add("依存型 " + type + " に複数の候補があるため " + selected.qualifiedName() + " を使用しました。");
		}
		return new Target(selected);
	}

	private List<JavaComponent> exactTypeNameCandidates(String type, List<JavaComponent> candidates) {
		String simpleTypeName = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
		return candidates.stream()
				.filter(candidate -> candidate.simpleName().equals(simpleTypeName))
				.distinct()
				.toList();
	}

	private JavaComponent selectCandidate(JavaComponent caller, List<JavaComponent> candidates) {
		return candidates.stream().min(Comparator
                .comparing((JavaComponent c) -> !c.packageName().equals(caller.packageName()))
                .thenComparing(JavaComponent::qualifiedName)).orElseThrow();
	}

	private boolean allowedLayerTransition(ComponentLayer from, ComponentLayer to) {
		return switch (from) {
			case CONTROLLER, SERVICE -> to == ComponentLayer.SERVICE
					|| to == ComponentLayer.COMPONENT
					|| to == ComponentLayer.REPOSITORY;
            case COMPONENT -> to == ComponentLayer.COMPONENT
					|| to == ComponentLayer.REPOSITORY;
			case REPOSITORY -> false;
		};
	}

	@SuppressWarnings("unchecked")
	private Optional<MethodDeclaration> nearestMethod(MethodCallExpr call) {
		return call.findAncestor(MethodDeclaration.class);
	}

	private String variableName(Expression scope) {
        return switch (scope) {
            case null -> StringUtils.EMPTY;
            case NameExpr name -> name.getNameAsString();
            case FieldAccessExpr field -> field.getNameAsString();
            default -> null;
        };
    }

	String build() {
		List<String> lines = new ArrayList<>();
		lines.add("sequenceDiagram");
		lines.add("    actor Client");
		participants.values().stream()
				.sorted(Comparator.comparingInt(component -> layerOrder(component.layer())))
				.forEach(component -> lines.add(
						"    participant " + alias(component) + " as " + escape(component.simpleName())));
		lines.addAll(flowLines);
		return "```mermaid\n" + String.join("\n", lines) + "\n```\n";
	}

	private int layerOrder(ComponentLayer layer) {
		return switch (layer) {
			case CONTROLLER -> 0;
			case SERVICE -> 1;
			case COMPONENT -> 2;
			case REPOSITORY -> 3;
		};
	}

	private String methodDisplayName(JavaMethod method) {
		if (method == null) {
			return StringUtils.EMPTY;
		}

		if (StringUtils.isNoneBlank(method.javadocSummary())) {
			return method.javadocSummary();
		}

		return method.name() + "()";
	}

	private String callLabel(MethodCallExpr call) {
		String args = call.getArguments().stream().map(Object::toString).map(this::shorten)
				.reduce((a, b) -> a + ", " + b).orElse("");
		return call.getNameAsString() + "(" + args + ")";
	}

	private String shorten(String value) {
		if (StringUtils.isBlank(value)) {
			return StringUtils.EMPTY;
		}

		String oneLine = value.replaceAll("\\s+", " ").trim();
		if (oneLine.length() <= 50) {
			return oneLine;
		}

		return oneLine.substring(0, 47) + "...";
	}

	private String alias(JavaComponent component) {
		if (component == null) {
			return StringUtils.EMPTY;
		}

		return "P_" + component.qualifiedName().replaceAll("[^A-Za-z0-9_]", "_");
	}

	private String escape(String value) {
		if (StringUtils.isBlank(value)) {
			return StringUtils.EMPTY;
		}

		return value
				.replace("\r", " ")
				.replace("\n", " ")
				.replace(";", "；")
				.replace(":", "：")
				.replace("<", "＜")
				.replace(">", "＞")
				.replace("&", "＆")
				.replace("#", "＃")
				.replaceAll("\\s+", " ")
				.trim();
	}

	private record Target(JavaComponent component) {
	}
}
