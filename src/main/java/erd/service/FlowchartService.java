package erd.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import erd.controller.dto.FlowchartResponse;
import erd.service.model.ComponentLayer;
import erd.service.model.JavaComponent;
import erd.service.model.JavaMethod;
import erd.service.model.SourceModel;

@Service
public class FlowchartService {

    private final SourceCodeAnalyzer sourceCodeAnalyzer;

    public FlowchartService(SourceCodeAnalyzer sourceCodeAnalyzer) {
        this.sourceCodeAnalyzer = sourceCodeAnalyzer;
    }

    public FlowchartResponse generate(String sourcePath, String controllerClass, String endpointMethod) {
        if (StringUtils.isBlank(controllerClass)) {
            throw new IllegalArgumentException("コントローラを選択してください。");
        }
        if (StringUtils.isBlank(endpointMethod)) {
            throw new IllegalArgumentException("エンドポイントを選択してください。");
        }

        SourceModel model = sourceCodeAnalyzer.analyze(sourcePath);
        JavaComponent controller = model.componentsByQualifiedName().get(controllerClass);
        if (controller == null || controller.layer() != ComponentLayer.CONTROLLER) {
            throw new IllegalArgumentException("指定したコントローラが見つかりません: " + controllerClass);
        }

        JavaMethod entryMethod = controller.methods().stream()
                .filter(method -> method.name().equals(endpointMethod))
                .filter(method -> sourceCodeAnalyzer.hasMappingAnnotation(method.declaration()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("指定したエンドポイントが見つかりません: " + endpointMethod));

        List<String> warnings = new ArrayList<>(model.warnings());
        FlowBuilder builder = new FlowBuilder(model, warnings);
        return new FlowchartResponse(builder.build(controller, entryMethod), warnings.stream().distinct().toList());
    }


    private static final class FlowBuilder {
        private static final int MAX_DEPTH = 12;
        private static final int MAX_CALLS = 250;

        private final SourceModel model;
        private final List<String> lines = new ArrayList<>();
        private final List<String> warnings;
        private final AtomicInteger ids = new AtomicInteger();
        private int callCount;

        private FlowBuilder(SourceModel model, List<String> warnings) {
            this.model = model;
            this.warnings = warnings;
        }

        String build(JavaComponent controller, JavaMethod method) {
            lines.add("graph TD");
            String start = node("開始: " + controller.simpleName() + "." + method.name() + "()", Shape.ROUNDED);
            String end = node("終了", Shape.ROUNDED);
            MethodContext context = new MethodContext(controller, method, end, 0, new HashSet<>());
            String key = methodKey(controller, method);
            context.stack().add(key);

            if (method.declaration().getBody().isEmpty()) {
                edge(start, end, null);
                warnings.add("選択したEndpointにメソッド本体がありません。");
            } else {
                List<String> exits = processBlock(method.declaration().getBody().orElseThrow(), List.of(start), context);
                connectAll(exits, end, null);
            }
            return String.join("\n", lines);
        }

        private List<String> processBlock(BlockStmt block, List<String> incoming, MethodContext context) {
            List<String> exits = new ArrayList<>(incoming);
            for (Statement statement : block.getStatements()) {
                if (exits.isEmpty()) break;
                exits = processStatement(statement, exits, context);
            }
            return exits;
        }

        private List<String> processStatement(Statement statement, List<String> incoming, MethodContext context) {
            if (statement.isBlockStmt()) return processBlock(statement.asBlockStmt(), incoming, context);
            if (statement.isIfStmt()) return processIf(statement.asIfStmt(), incoming, context);
            if (statement.isSwitchStmt()) return processSwitch(statement.asSwitchStmt(), incoming, context);
            if (statement.isTryStmt()) return processTry(statement.asTryStmt(), incoming, context);
            if (isLoop(statement)) return processLoopBodyOnce(statement, incoming, context);
            if (statement.isBreakStmt() || statement.isContinueStmt() || statement.isEmptyStmt()) return incoming;

            if (statement.isReturnStmt()) {
                // 戻り値そのものはフローチャートに表示しない。
                // return式内に追跡対象のService/Component呼び出しがある場合は、その呼び出しだけ展開する。
                List<String> exits = processCalls(statement.asReturnStmt().getExpression().orElse(null), incoming, context);
                connectAll(exits, context.returnNode(), null);
                return List.of();
            }
            if (statement.isThrowStmt()) {
                List<String> exits = processCalls(statement.asThrowStmt().getExpression(), incoming, context);
                return processTerminal("throw " + statement.asThrowStmt().getExpression(), exits, context);
            }
            if (statement.isExpressionStmt()) {
                Expression expression = statement.asExpressionStmt().getExpression();
                List<String> exits = processCalls(expression, incoming, context);

                // 変数宣言・代入はノードとして表示しない。右辺に追跡対象メソッド呼び出しが
                // 含まれている場合は processCalls() でその呼び出しだけを展開する。
                if (expression instanceof VariableDeclarationExpr || expression instanceof AssignExpr) {
                    return exits;
                }
                if (expression.isMethodCallExpr() && isTrackedCall(context, expression.asMethodCallExpr())) {
                    return exits;
                }
                return appendLinear(clean(expression.toString()), exits);
            }
            return appendLinear(clean(statement.toString()), incoming);
        }

        private List<String> processIf(IfStmt ifStmt, List<String> incoming, MethodContext context) {
            List<String> conditionIncoming = processCalls(ifStmt.getCondition(), incoming, context);
            Optional<String> visibleCondition = visibleCondition(ifStmt.getCondition());

            // null/空チェックだけで構成されるIFは判定ノードを表示しない。
            // 中の処理は失わないよう、then/else双方を同じ入力から解析して合流させる。
            if (visibleCondition.isEmpty()) {
                List<String> thenExits = processStatement(ifStmt.getThenStmt(), conditionIncoming, context);
                List<String> elseExits = ifStmt.getElseStmt()
                        .map(elseStmt -> processStatement(elseStmt, conditionIncoming, context))
                        .orElse(conditionIncoming);
                return mergeDistinct(thenExits, elseExits);
            }

            String decision = node("IF: " + clean(visibleCondition.orElseThrow()), Shape.DIAMOND);
            connectAll(conditionIncoming, decision, null);

            List<String> thenExits = processBranch(ifStmt.getThenStmt(), decision, "Yes", context);
            List<String> elseExits;
            if (ifStmt.getElseStmt().isPresent()) {
                Statement elseStmt = ifStmt.getElseStmt().orElseThrow();
                if (elseStmt.isIfStmt()) {
                    elseExits = processStatementWithLabel(elseStmt, decision, "No", context);
                } else {
                    elseExits = processBranch(elseStmt, decision, "No", context);
                }
            } else {
                String pass = node("条件不成立", Shape.PROCESS);
                edge(decision, pass, "No");
                elseExits = List.of(pass);
            }
            return merge(thenExits, elseExits);
        }

        /**
         * 表示対象の条件式を返す。null/空チェックは条件式から取り除く。
         * 例: value != null && value.isActive() -> value.isActive()
         */
        private Optional<String> visibleCondition(Expression expression) {
            Expression expr = unwrap(expression);
            if (isNullOrEmptyCheck(expr)) return Optional.empty();

            if (expr instanceof BinaryExpr binary
                    && (binary.getOperator() == BinaryExpr.Operator.AND
                    || binary.getOperator() == BinaryExpr.Operator.OR)) {
                Optional<String> left = visibleCondition(binary.getLeft());
                Optional<String> right = visibleCondition(binary.getRight());
                if (left.isEmpty()) return right;
                if (right.isEmpty()) return left;
                String operator = binary.getOperator() == BinaryExpr.Operator.AND ? " && " : " || ";
                return Optional.of("(" + left.orElseThrow() + operator + right.orElseThrow() + ")");
            }

            if (expr instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                Optional<String> inner = visibleCondition(unary.getExpression());
                return inner.map(value -> "!(" + value + ")");
            }

            return Optional.of(clean(expr.toString()));
        }

        private Expression unwrap(Expression expression) {
            Expression current = expression;
            while (current.isEnclosedExpr()) current = current.asEnclosedExpr().getInner();
            return current;
        }

        private boolean isNullOrEmptyCheck(Expression expression) {
            Expression expr = unwrap(expression);

            if (expr instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return isNullOrEmptyCheck(unary.getExpression());
            }

            if (expr instanceof BinaryExpr binary) {
                BinaryExpr.Operator op = binary.getOperator();
                if ((op == BinaryExpr.Operator.EQUALS || op == BinaryExpr.Operator.NOT_EQUALS)
                        && (isNullLiteral(binary.getLeft()) || isNullLiteral(binary.getRight()))) {
                    return true;
                }
                if ((op == BinaryExpr.Operator.EQUALS || op == BinaryExpr.Operator.NOT_EQUALS)
                        && (isEmptyStringLiteral(binary.getLeft()) || isEmptyStringLiteral(binary.getRight()))) {
                    return true;
                }
                if (isZeroComparison(binary)) return true;
            }

            if (expr instanceof MethodCallExpr call) {
                String name = call.getNameAsString();
                String scope = call.getScope().map(Object::toString).orElse("");

                // org.apache.commons.lang3.StringUtils
                if (scope.endsWith("StringUtils")
                        && Set.of("isEmpty", "isNotEmpty", "isBlank", "isNotBlank").contains(name)) {
                    return true;
                }
                // java.util.Objects
                if (scope.endsWith("Objects") && Set.of("isNull", "nonNull").contains(name)) {
                    return true;
                }
                // CollectionUtils/ObjectUtils/MapUtilsなど一般的な空チェックutility。
                if ((scope.endsWith("CollectionUtils") || scope.endsWith("ObjectUtils") || scope.endsWith("MapUtils"))
                        && Set.of("isEmpty", "isNotEmpty").contains(name)) {
                    return true;
                }
                // value.isEmpty(), value.isBlank(), optional.isPresent() など。
                if (call.getArguments().isEmpty()
                        && Set.of("isEmpty", "isBlank", "isPresent").contains(name)) {
                    return true;
                }
                // Objects.equals(value, "") のような空文字比較。
                if (scope.endsWith("Objects") && name.equals("equals") && call.getArguments().size() == 2
                        && call.getArguments().stream().anyMatch(this::isEmptyStringLiteral)) {
                    return true;
                }
                // "".equals(value) / value.equals("")
                if (name.equals("equals") && call.getArguments().size() == 1
                        && (call.getScope().map(this::isEmptyStringLiteral).orElse(false)
                        || isEmptyStringLiteral(call.getArgument(0)))) {
                    return true;
                }
            }
            return false;
        }

        private boolean isNullLiteral(Expression expression) {
            return unwrap(expression) instanceof NullLiteralExpr;
        }

        private boolean isEmptyStringLiteral(Expression expression) {
            Expression expr = unwrap(expression);
            return expr instanceof StringLiteralExpr literal && literal.getValue().isEmpty();
        }

        private boolean isZeroComparison(BinaryExpr binary) {
            BinaryExpr.Operator op = binary.getOperator();
            if (!(op == BinaryExpr.Operator.EQUALS || op == BinaryExpr.Operator.NOT_EQUALS
                    || op == BinaryExpr.Operator.GREATER || op == BinaryExpr.Operator.LESS_EQUALS)) {
                return false;
            }
            return (isSizeOrLength(binary.getLeft()) && isZero(binary.getRight()))
                    || (isSizeOrLength(binary.getRight()) && isZero(binary.getLeft()));
        }

        private boolean isSizeOrLength(Expression expression) {
            Expression expr = unwrap(expression);
            if (!(expr instanceof MethodCallExpr call) || !call.getArguments().isEmpty()) return false;
            return Set.of("size", "length").contains(call.getNameAsString());
        }

        private boolean isZero(Expression expression) {
            Expression expr = unwrap(expression);
            return expr instanceof IntegerLiteralExpr literal && "0".equals(literal.getValue());
        }

        private List<String> processStatementWithLabel(Statement statement, String from, String label, MethodContext context) {
            String marker = node("else", Shape.PROCESS);
            edge(from, marker, label);
            return processStatement(statement, List.of(marker), context);
        }

        private List<String> processSwitch(SwitchStmt switchStmt, List<String> incoming, MethodContext context) {
            List<String> selectorIncoming = processCalls(switchStmt.getSelector(), incoming, context);
            String decision = node("SWITCH: " + clean(switchStmt.getSelector().toString()), Shape.DIAMOND);
            connectAll(selectorIncoming, decision, null);
            List<String> exits = new ArrayList<>();

            for (SwitchEntry entry : switchStmt.getEntries()) {
                String label = entry.getLabels().isEmpty()
                        ? "default"
                        : entry.getLabels().stream().map(Node::toString).map(this::clean)
                        .reduce((a, b) -> a + ", " + b).orElse("case");
                String caseNode = node("CASE: " + label, Shape.PROCESS);
                edge(decision, caseNode, label);
                List<String> caseExits = List.of(caseNode);
                for (Statement statement : entry.getStatements()) {
                    if (statement instanceof BreakStmt) break;
                    if (caseExits.isEmpty()) break;
                    caseExits = processStatement(statement, caseExits, context);
                }
                exits.addAll(caseExits);
            }
            return switchStmt.getEntries().isEmpty() ? List.of(decision) : exits;
        }

        private List<String> processTry(TryStmt tryStmt, List<String> incoming, MethodContext context) {
            // processBlock may return List.of(...), so make it mutable before merging catch exits.
            List<String> exits = new ArrayList<>(processBlock(tryStmt.getTryBlock(), incoming, context));
            for (var catchClause : tryStmt.getCatchClauses()) {
                String catchNode = node("catch: " + clean(catchClause.getParameter().toString()), Shape.PROCESS);
                connectAll(incoming, catchNode, "例外");
                exits.addAll(processBlock(catchClause.getBody(), List.of(catchNode), context));
            }
            if (tryStmt.getFinallyBlock().isPresent()) {
                exits = processBlock(tryStmt.getFinallyBlock().orElseThrow(), exits, context);
            }
            return exits;
        }

        private List<String> processLoopBodyOnce(Statement statement, List<String> incoming, MethodContext context) {
            Statement body = null;
            if (statement instanceof ForStmt s) body = s.getBody();
            else if (statement instanceof ForEachStmt s) body = s.getBody();
            else if (statement instanceof WhileStmt s) body = s.getBody();
            else if (statement instanceof DoStmt s) body = s.getBody();
            return body == null ? incoming : processStatement(body, incoming, context);
        }

        private boolean isLoop(Statement statement) {
            return statement instanceof ForStmt || statement instanceof ForEachStmt
                    || statement instanceof WhileStmt || statement instanceof DoStmt;
        }

        private List<String> processBranch(Statement branch, String decision, String label, MethodContext context) {
            String first = node(label + " 分岐", Shape.PROCESS);
            edge(decision, first, label);
            return processStatement(branch, List.of(first), context);
        }

        private List<String> processCalls(Expression expression, List<String> incoming, MethodContext context) {
            if (expression == null || incoming.isEmpty()) return incoming;
            List<MethodCallExpr> calls = expression.findAll(MethodCallExpr.class).stream()
                    .sorted(Comparator
                            .comparingInt((MethodCallExpr call) -> nestingDepth(call, expression)).reversed()
                            .thenComparing(call -> call.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE))
                            .thenComparing(call -> call.getBegin().map(p -> p.column).orElse(Integer.MAX_VALUE)))
                    .toList();

            List<String> exits = incoming;
            for (MethodCallExpr call : calls) {
                exits = expandCall(call, exits, context);
            }
            return exits;
        }

        private int nestingDepth(Node node, Node ancestor) {
            int depth = 0;
            Node current = node.getParentNode().orElse(null);
            while (current != null && current != ancestor) {
                if (current instanceof MethodCallExpr) depth++;
                current = current.getParentNode().orElse(null);
            }
            return depth;
        }

        private List<String> expandCall(MethodCallExpr call, List<String> incoming, MethodContext context) {
            ResolvedCall resolved = resolveCall(context.component(), context.method(), call);
            if (resolved == null) return incoming;
            if (context.depth() >= MAX_DEPTH || callCount >= MAX_CALLS) {
                warnings.add("呼び出し追跡が上限に達したため、一部を省略しました。");
                return incoming;
            }

            String key = methodKey(resolved.component(), resolved.method());
            if (context.stack().contains(key)) {
                String omitted = node("再帰呼び出し省略: " + resolved.component().simpleName() + "." + resolved.method().name() + "()", Shape.PROCESS);
                connectAll(incoming, omitted, null);
                return List.of(omitted);
            }

            callCount++;
            String enter = node(resolved.component().simpleName() + "." + resolved.method().name() + "()", Shape.SUBROUTINE);
            connectAll(incoming, enter, null);
            String leave = node("戻り: " + resolved.component().simpleName() + "." + resolved.method().name() + "()", Shape.PROCESS);

            Set<String> childStack = new HashSet<>(context.stack());
            childStack.add(key);
            MethodContext child = new MethodContext(resolved.component(), resolved.method(), leave, context.depth() + 1, childStack);
            if (resolved.method().declaration().getBody().isEmpty()) {
                edge(enter, leave, null);
            } else {
                List<String> exits = processBlock(resolved.method().declaration().getBody().orElseThrow(), List.of(enter), child);
                connectAll(exits, leave, null);
            }
            return List.of(leave);
        }

        private boolean isTrackedCall(MethodContext context, MethodCallExpr call) {
            return resolveCall(context.component(), context.method(), call) != null;
        }

        private ResolvedCall resolveCall(JavaComponent caller, JavaMethod method, MethodCallExpr call) {
            Optional<JavaMethod> internal = resolveInternalMethod(caller, call);
            if (internal.isPresent()) return new ResolvedCall(caller, internal.get());

            if (call.getScope().isEmpty()) return null;
            String variable = variableName(call.getScope().get());
            if (variable == null) return null;

            String type = method.variableTypes().get(variable);
            if (type == null) type = caller.dependencyVariables().get(variable);
            if (type == null) return null;

            List<JavaComponent> candidates = model.componentsByTypeName().getOrDefault(type, List.of());
            if (candidates.isEmpty()) return null;
            List<JavaComponent> exactCandidates = exactTypeNameCandidates(type, candidates);
            List<JavaComponent> selectionPool = exactCandidates.isEmpty() ? candidates : exactCandidates;
            JavaComponent target = selectCandidate(caller, selectionPool);
            if (!allowedLayerTransition(caller.layer(), target.layer())) return null;

            Optional<JavaMethod> targetMethod = resolveMethod(target, call);
            if (targetMethod.isEmpty()) return null;
            if (exactCandidates.size() > 1) {
                warnings.add("依存型 " + type + " に複数の候補があるため " + target.qualifiedName() + " を使用しました。");
            }
            return new ResolvedCall(target, targetMethod.get());
        }

        private Optional<JavaMethod> resolveInternalMethod(JavaComponent caller, MethodCallExpr call) {
            boolean internalScope = call.getScope().isEmpty()
                    || call.getScope().filter(ThisExpr.class::isInstance).isPresent();
            return internalScope ? resolveMethod(caller, call) : Optional.empty();
        }

        private Optional<JavaMethod> resolveMethod(JavaComponent component, MethodCallExpr call) {
            List<JavaMethod> sameName = component.methods().stream()
                    .filter(candidate -> candidate.name().equals(call.getNameAsString()))
                    .toList();
            if (sameName.isEmpty()) return Optional.empty();
            int argumentCount = call.getArguments().size();
            return sameName.stream()
                    .filter(candidate -> candidate.declaration().getParameters().size() == argumentCount)
                    .findFirst()
                    .or(() -> sameName.stream().findFirst());
        }

        private List<JavaComponent> exactTypeNameCandidates(String type, List<JavaComponent> candidates) {
            String simpleTypeName = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
            return candidates.stream()
                    .filter(candidate -> candidate.simpleName().equals(simpleTypeName))
                    .distinct()
                    .toList();
        }

        private JavaComponent selectCandidate(JavaComponent caller, List<JavaComponent> candidates) {
            return candidates.stream()
                    .sorted(Comparator
                            .comparing((JavaComponent c) -> !c.packageName().equals(caller.packageName()))
                            .thenComparing(JavaComponent::qualifiedName))
                    .findFirst().orElseThrow();
        }

        private boolean allowedLayerTransition(ComponentLayer from, ComponentLayer to) {
            return switch (from) {
                case CONTROLLER -> to == ComponentLayer.SERVICE || to == ComponentLayer.COMPONENT;
                case SERVICE -> to == ComponentLayer.SERVICE || to == ComponentLayer.COMPONENT;
                case COMPONENT -> to == ComponentLayer.SERVICE || to == ComponentLayer.COMPONENT;
                case REPOSITORY -> false;
            };
        }

        private String variableName(Expression scope) {
            if (scope instanceof NameExpr name) return name.getNameAsString();
            if (scope instanceof FieldAccessExpr field) return field.getNameAsString();
            return null;
        }

        private String methodKey(JavaComponent component, JavaMethod method) {
            return component.qualifiedName() + "#" + method.name() + "/" + method.declaration().getParameters().size();
        }

        private List<String> processTerminal(String text, List<String> incoming, MethodContext context) {
            String terminal = node(clean(text), Shape.PROCESS);
            connectAll(incoming, terminal, null);
            edge(terminal, context.returnNode(), null);
            return List.of();
        }

        private List<String> appendLinear(String text, List<String> incoming) {
            if (StringUtils.isBlank(text)) return incoming;
            String next = node(text, Shape.PROCESS);
            connectAll(incoming, next, null);
            return List.of(next);
        }

        private List<String> merge(List<String> a, List<String> b) {
            List<String> result = new ArrayList<>(a.size() + b.size());
            result.addAll(a);
            result.addAll(b);
            return result;
        }

        private List<String> mergeDistinct(List<String> a, List<String> b) {
            return new ArrayList<>(new LinkedHashSet<>(merge(a, b)));
        }

        private void connectAll(List<String> sources, String target, String label) {
            for (String source : sources) edge(source, target, label);
        }

        private String node(String text, Shape shape) {
            String id = "N" + ids.incrementAndGet();
            String escaped = escape(text);
            if (shape == Shape.DIAMOND) lines.add("    " + id + "{\"" + escaped + "\"}");
            else if (shape == Shape.ROUNDED) lines.add("    " + id + "([\"" + escaped + "\"])");
            else if (shape == Shape.SUBROUTINE) lines.add("    " + id + "[[\"" + escaped + "\"]]");
            else lines.add("    " + id + "[\"" + escaped + "\"]");
            return id;
        }

        private void edge(String from, String to, String label) {
            if (label == null) lines.add("    " + from + " --> " + to);
            else lines.add("    " + from + " -- \"" + escape(label) + "\" --> " + to);
        }

        private String clean(String text) {
            if (text == null) return "";
            return text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        }

        private String escape(String text) {
            return clean(text)
                    .replace("\\", "\\\\")
                    .replace("\"", "'")
                    .replace("[", "(")
                    .replace("]", ")")
                    .replace("{", "(")
                    .replace("}", ")")
                    .replace("<", "＜")
                    .replace(">", "＞")
                    .replace("&", "＆")
                    .replace("#", "＃")
                    .replace(";", "；");
        }

        private record MethodContext(JavaComponent component, JavaMethod method, String returnNode, int depth, Set<String> stack) {}
        private record ResolvedCall(JavaComponent component, JavaMethod method) {}
        private enum Shape { PROCESS, DIAMOND, ROUNDED, SUBROUTINE }
    }
}
