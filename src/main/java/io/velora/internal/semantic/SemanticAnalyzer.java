package io.velora.internal.semantic;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;
import io.velora.api.function.ApiRegistry;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.event.EventDescriptor;
import io.velora.api.event.EventRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingEditorDescriptor;
import io.velora.api.setting.SettingKind;
import io.velora.api.setting.SettingValidationResult;
import io.velora.api.setting.SettingValue;
import io.velora.api.type.EnumType;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.ast.*;
import io.velora.internal.setting.SettingValidator;

import java.util.*;

/**
 * Semantic analyzer: resolves symbols, infers/checks types, builds setting
 * descriptors and produces a {@link ResolvedScript}.
 */
public final class SemanticAnalyzer {

    private final TypeRegistry typeRegistry;
    private final SettingRegistry settingRegistry;
    private final ApiRegistry apiRegistry;
    private final ConstantRegistry constantRegistry;
    private final EventRegistry eventRegistry;
    private final JavaImportRegistry javaImportRegistry;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private Map<String, ResolvedScript.ResolvedFunction> resolvedFunctions;
    private Map<String, String> importNamespaces = Map.of();

    public SemanticAnalyzer(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                            ApiRegistry apiRegistry, ConstantRegistry constantRegistry) {
        this(typeRegistry, settingRegistry, apiRegistry, constantRegistry, null, null);
    }

    public SemanticAnalyzer(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                            ApiRegistry apiRegistry, ConstantRegistry constantRegistry,
                            EventRegistry eventRegistry) {
        this(typeRegistry, settingRegistry, apiRegistry, constantRegistry, eventRegistry, null);
    }

    public SemanticAnalyzer(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                            ApiRegistry apiRegistry, ConstantRegistry constantRegistry,
                            EventRegistry eventRegistry, JavaImportRegistry javaImportRegistry) {
        this.typeRegistry = typeRegistry;
        this.settingRegistry = settingRegistry;
        this.apiRegistry = apiRegistry;
        this.constantRegistry = constantRegistry;
        this.eventRegistry = eventRegistry;
        this.javaImportRegistry = javaImportRegistry;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public ResolvedScript analyze(ScriptNode script) {
        diagnostics.clear();
        importNamespaces = resolveImports(script);
        validateScriptAnnotations(script);
        ResolvedScript.ScriptMetadata metadata = extractMetadata(script);
        List<SettingDescriptor> settings = buildSettings(script.settingBlock());
        Map<String, ResolvedScript.ResolvedProperty> properties = new LinkedHashMap<>();
        Map<String, ResolvedScript.ResolvedFunction> functions = new LinkedHashMap<>();
        Map<String, FunctionNode> functionNodes = new LinkedHashMap<>();
        Map<LifecycleHook, ResolvedScript.ResolvedFunction> lifecycle = new LinkedHashMap<>();
        List<ResolvedScript.ResolvedEventHandler> eventHandlers = new ArrayList<>();
        Set<String> persistentIds = new HashSet<>();
        Set<String> inferredNumericProperties = new HashSet<>();
        Scope scriptScope = new Scope();

        for (SettingDescriptor setting : settings) {
            scriptScope.define(new Symbol(setting.id(), setting.type(), Symbol.Kind.SETTING));
        }

        int fieldIndex = 0;
        int staticIndex = 0;
        for (ScriptMemberNode member : script.members()) {
            if (!(member instanceof PropertyDeclarationNode prop)) continue;
            if (properties.containsKey(prop.name()) || scriptScope.definesLocally(prop.name())) {
                error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate field: " + prop.name(), prop.line(), prop.column());
                continue;
            }
            VeloraType propType = prop.declaredType() != null ? resolveType(prop.declaredType(), prop) : inferTypeFromInitializer(prop.initializer());
            if (propType == null || propType == VeloraTypes.UNIT) {
                error(DiagnosticCode.SEMANTIC_UNTYPED_DECLARATION, "Cannot infer type of field '" + prop.name() + "'", prop.line(), prop.column());
                propType = VeloraTypes.UNIT;
            }
            if (prop.persistent()) {
                String persistentId = prop.persistentId() != null ? prop.persistentId() : prop.name();
                if (!isPersistableType(propType)) error(DiagnosticCode.SEMANTIC_INVALID_PERSISTENT_TYPE, "Persistent field '" + prop.name() + "' uses unsupported type " + propType.name(), prop.line(), prop.column());
                if (persistentId.isBlank()) error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Persistent id cannot be blank", prop.line(), prop.column());
                else if (!persistentIds.add(persistentId)) error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate persistent id: " + persistentId, prop.line(), prop.column());
            }
            Object constValue = null;
            if (prop.initializer() != null) {
                if (prop.isStatic() && prop.isConst()) error(DiagnosticCode.SEMANTIC_STATIC_CONST_CONFLICT, "Field '" + prop.name() + "' cannot be both static and const", prop.line(), prop.column());
                ConstantEvaluation evaluation = evaluateConstant(prop.initializer(), properties);
                if (!evaluation.constant()) {
                    error(prop.isConst() ? DiagnosticCode.SEMANTIC_CONST_RUNTIME_INIT : DiagnosticCode.SEMANTIC_NON_CONSTANT_FIELD_INIT,
                            "Field '" + prop.name() + "' initializer must be compile-time evaluable", prop.line(), prop.column());
                } else {
                    constValue = evaluation.value();
                    VeloraType valueType = constantType(constValue);
                    if (valueType != null && !isAssignableExpression(prop.initializer(), valueType, propType)) {
                        error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Field '" + prop.name() + "' expects " + propType.name() + ", got " + valueType.name(), prop.line(), prop.column());
                    }
                }
            }
            int index = prop.isStatic() ? staticIndex++ : fieldIndex++;
            ResolvedScript.ResolvedProperty resolved = new ResolvedScript.ResolvedProperty(
                    prop.name(), propType, !prop.isConst(), prop.persistent(), prop.persistentId(), index, prop.isStatic(), prop.isConst(), constValue);
            properties.put(prop.name(), resolved);
            if (prop.declaredType() == null && !prop.isConst() && isNumeric(propType)) inferredNumericProperties.add(prop.name());
            scriptScope.define(new Symbol(prop.name(), propType, prop.isConst() ? Symbol.Kind.CONST_PROPERTY : Symbol.Kind.PROPERTY));
        }

        int functionIndex = 0;
        for (ScriptMemberNode member : script.members()) {
            if (!(member instanceof FunctionNode fn)) continue;
            FunctionRole role = resolveFunctionRole(fn);
            List<ResolvedScript.ResolvedParam> params = resolveParameters(fn, properties);
            if (role.kind == FunctionRoleKind.NORMAL) {
                if (functions.containsKey(fn.name()) || scriptScope.definesLocally(fn.name())) {
                    error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate function: " + fn.name(), fn.line(), fn.column());
                    continue;
                }
                VeloraType returnType = fn.returnType() != null ? resolveType(fn.returnType(), fn) : null;
                ResolvedScript.ResolvedFunction resolved = new ResolvedScript.ResolvedFunction(
                        fn.name(), params, returnType, fn.returnType() != null, fn.suspending(), fn.body(), functionIndex++, false);
                functions.put(fn.name(), resolved);
                functionNodes.put(fn.name(), fn);
                scriptScope.define(new Symbol(fn.name(), returnType != null ? returnType : VeloraTypes.UNIT, Symbol.Kind.FUNCTION));
                continue;
            }
            if (role.kind == FunctionRoleKind.LIFECYCLE) {
                if (!params.isEmpty()) error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "@" + role.annotation + " lifecycle handler does not accept parameters", fn.line(), fn.column());
                if (fn.suspending() && role.lifecycle != LifecycleHook.ON_RUN) {
                    error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "@" + role.annotation + " lifecycle handler must be synchronous", fn.line(), fn.column());
                }
                if (fn.returnType() != null && resolveType(fn.returnType(), fn) != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_WRONG_RETURN_TYPE, "Lifecycle handlers cannot return values", fn.line(), fn.column());
                }
                ResolvedScript.ResolvedFunction resolved = new ResolvedScript.ResolvedFunction(
                        fn.name(), List.of(), VeloraTypes.UNIT, true, fn.suspending(), fn.body(), functionIndex++, true);
                if (lifecycle.putIfAbsent(role.lifecycle, resolved) != null) {
                    error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate @" + role.annotation + " handler", fn.line(), fn.column());
                }
                continue;
            }
            EventDescriptor event = role.event;
            boolean payloadless = event.payloadType().nonNull() == VeloraTypes.UNIT || event.payloadType().nonNull() == VeloraTypes.NOTHING;
            String paramName = null;
            VeloraType paramType = VeloraTypes.UNIT;
            if (params.isEmpty()) {
                if (!payloadless) error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "@" + role.annotation + " requires a " + event.payloadType().name() + " parameter", fn.line(), fn.column());
            } else if (params.size() != 1) {
                error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "@" + role.annotation + " accepts exactly one event parameter", fn.line(), fn.column());
            } else {
                ResolvedScript.ResolvedParam param = params.get(0);
                paramName = param.name();
                paramType = param.type();
                if (!eventPayloadAssignable(event.payloadType(), paramType)) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "@" + role.annotation + " payload is " + event.payloadType().name() + ", handler expects " + paramType.name(), fn.line(), fn.column());
                }
            }
            if (fn.returnType() != null && resolveType(fn.returnType(), fn) != VeloraTypes.UNIT) {
                error(DiagnosticCode.SEMANTIC_WRONG_RETURN_TYPE, "Event handlers cannot return values", fn.line(), fn.column());
            }
            String functionName = "$event$" + event.id().replaceAll("[^A-Za-z0-9_$]", "_") + "$" + functionIndex;
            eventHandlers.add(new ResolvedScript.ResolvedEventHandler(
                    event.id(), functionName, paramName, paramType, fn.suspending(), fn.body(), functionIndex++));
        }

        this.resolvedFunctions = functions;
        widenInferredNumericProperties(script, inferredNumericProperties, properties, scriptScope);
        inferFunctionReturnTypes(functionNodes, functions, scriptScope);
        for (ResolvedScript.ResolvedFunction function : functions.values()) {
            scriptScope.define(new Symbol(function.name(), function.returnType(), Symbol.Kind.FUNCTION));
        }
        for (ResolvedScript.ResolvedFunction function : functions.values()) checkFunction(function, scriptScope);
        for (ResolvedScript.ResolvedFunction function : lifecycle.values()) checkFunction(function, scriptScope);
        for (ResolvedScript.ResolvedEventHandler handler : eventHandlers) {
            List<ResolvedScript.ResolvedParam> params = handler.parameterName() == null ? List.of()
                    : List.of(new ResolvedScript.ResolvedParam(handler.parameterName(), handler.parameterType(), false, 0, null));
            ResolvedScript.ResolvedFunction function = new ResolvedScript.ResolvedFunction(
                    handler.functionName(), params, VeloraTypes.UNIT, true, handler.suspending(), handler.body(), handler.functionIndex(), true);
            checkFunction(function, scriptScope);
        }

        ResolvedScript result = new ResolvedScript(metadata, settings, properties, functions, lifecycle, eventHandlers, 2);
        result.setApiRegistry(apiRegistry);
        result.setImportNamespaces(importNamespaces);
        return result;
    }

    private void widenInferredNumericProperties(ScriptNode script, Set<String> candidates,
                                                Map<String, ResolvedScript.ResolvedProperty> properties, Scope scriptScope) {
        if (candidates.isEmpty()) return;
        boolean changed;
        int passes = 0;
        do {
            changed = false;
            for (ScriptMemberNode member : script.members()) {
                if (member instanceof FunctionNode function) {
                    Set<String> shadowed = new HashSet<>();
                    for (ParameterNode parameter : function.parameters()) shadowed.add(parameter.name());
                    changed |= widenInferredNumericProperties(function.body(), candidates, properties, scriptScope, shadowed);
                }
            }
        } while (changed && ++passes <= candidates.size());
    }

    private boolean widenInferredNumericProperties(BlockNode block, Set<String> candidates,
                                                   Map<String, ResolvedScript.ResolvedProperty> properties,
                                                   Scope scriptScope, Set<String> inheritedShadowed) {
        if (block == null) return false;
        boolean changed = false;
        Set<String> shadowed = new HashSet<>(inheritedShadowed);
        for (StatementNode statement : block.statements()) {
            if (statement instanceof VariableDeclarationNode variable) shadowed.add(variable.name());
            if (statement instanceof ExpressionStatementNode expression
                    && expression.expression() instanceof AssignmentExpressionNode assignment
                    && "=".equals(assignment.operator())
                    && assignment.target() instanceof IdentifierExpressionNode identifier
                    && candidates.contains(identifier.name())
                    && !shadowed.contains(identifier.name())) {
                ResolvedScript.ResolvedProperty property = properties.get(identifier.name());
                VeloraType valueType = inferExpressionType(assignment.value(), scriptScope, null);
                if (property != null && valueType != null && isNumeric(valueType)) {
                    VeloraType widened = commonType(property.type(), valueType);
                    if (widened != null && widened != property.type()) {
                        Object value = widenNumericConstant(property.constValue(), widened);
                        ResolvedScript.ResolvedProperty replacement = new ResolvedScript.ResolvedProperty(
                                property.name(), widened, property.mutable(), property.persistent(), property.persistentId(),
                                property.fieldIndex(), property.isStatic(), property.isConst(), value);
                        properties.put(property.name(), replacement);
                        scriptScope.define(new Symbol(property.name(), widened, Symbol.Kind.PROPERTY));
                        changed = true;
                    }
                }
            }
            if (statement instanceof IfStatementNode branch) {
                changed |= widenInferredNumericProperties(branch.thenBlock(), candidates, properties, scriptScope, shadowed);
                changed |= widenInferredNumericProperties(branch.elseBlock(), candidates, properties, scriptScope, shadowed);
            } else if (statement instanceof WhileStatementNode loop) {
                changed |= widenInferredNumericProperties(loop.body(), candidates, properties, scriptScope, shadowed);
            } else if (statement instanceof ForStatementNode loop) {
                Set<String> loopShadowed = new HashSet<>(shadowed);
                loopShadowed.add(loop.variable());
                changed |= widenInferredNumericProperties(loop.body(), candidates, properties, scriptScope, loopShadowed);
            } else if (statement instanceof WhenStatementNode when) {
                for (WhenStatementNode.Case branch : when.cases()) {
                    changed |= widenInferredNumericProperties(branch.body(), candidates, properties, scriptScope, shadowed);
                }
                changed |= widenInferredNumericProperties(when.elseBody(), candidates, properties, scriptScope, shadowed);
            }
        }
        return changed;
    }

    private Object widenNumericConstant(Object value, VeloraType type) {
        if (!(value instanceof Number number)) return value;
        if (type == VeloraTypes.LONG) return number.longValue();
        if (type == VeloraTypes.FLOAT) return number.floatValue();
        if (type == VeloraTypes.DOUBLE) return number.doubleValue();
        if (type == VeloraTypes.INT) return number.intValue();
        if (type == VeloraTypes.BYTE) return number.byteValue();
        return value;
    }

    private List<ResolvedScript.ResolvedParam> resolveParameters(FunctionNode fn, Map<String, ResolvedScript.ResolvedProperty> properties) {
        List<ResolvedScript.ResolvedParam> params = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean optional = false;
        for (int i = 0; i < fn.parameters().size(); i++) {
            ParameterNode parameter = fn.parameters().get(i);
            VeloraType type = resolveType(parameter.type(), parameter);
            if (type == null) type = VeloraTypes.UNIT;
            if (!names.add(parameter.name())) error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate parameter: " + parameter.name(), parameter.line(), parameter.column());
            if (parameter.hasDefault()) optional = true;
            else if (optional) error(DiagnosticCode.SEMANTIC_DEFAULT_BEFORE_REQUIRED, "Required parameter '" + parameter.name() + "' follows an optional parameter", parameter.line(), parameter.column());
            Object defaultValue = null;
            if (parameter.hasDefault()) {
                ConstantEvaluation evaluation = evaluateConstant(parameter.defaultValue(), properties);
                if (!evaluation.constant()) {
                    error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Default value for '" + parameter.name() + "' must be compile-time evaluable", parameter.line(), parameter.column());
                } else {
                    defaultValue = evaluation.value();
                    VeloraType defaultType = constantType(defaultValue);
                    if (defaultValue == null) {
                        if (!type.isNullable()) error(DiagnosticCode.SEMANTIC_PRIMITIVE_NULL, "Default value for '" + parameter.name() + "' cannot be null", parameter.line(), parameter.column());
                    } else if (defaultType != null && !isAssignableExpression(parameter.defaultValue(), defaultType, type)) {
                        error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Default value for '" + parameter.name() + "' expects " + type.name() + ", got " + defaultType.name(), parameter.line(), parameter.column());
                    }
                }
            }
            params.add(new ResolvedScript.ResolvedParam(parameter.name(), type, parameter.hasDefault(), i, defaultValue));
        }
        return params;
    }

    private FunctionRole resolveFunctionRole(FunctionNode function) {
        FunctionRole role = FunctionRole.normal();
        for (AnnotationNode annotation : function.annotations()) {
            if (!annotation.positionalArgs().isEmpty() || !annotation.namedArgs().isEmpty()) {
                error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@" + annotation.name() + " handler annotations do not accept arguments", annotation.line(), annotation.column());
            }
            LifecycleHook lifecycle = lifecycleAnnotation(annotation.name());
            EventDescriptor event = eventRegistry != null ? eventRegistry.findByScriptName(annotation.name()) : null;
            if (lifecycle == null && event == null) {
                error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Unknown function annotation @" + annotation.name(), annotation.line(), annotation.column());
                continue;
            }
            if (role.kind != FunctionRoleKind.NORMAL) {
                error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Only one lifecycle or event annotation is allowed on a function", annotation.line(), annotation.column());
                continue;
            }
            role = lifecycle != null ? FunctionRole.lifecycle(annotation.name(), lifecycle) : FunctionRole.event(annotation.name(), event);
        }
        return role;
    }

    private LifecycleHook lifecycleAnnotation(String name) {
        return switch (name) {
            case "Load" -> LifecycleHook.ON_LOAD;
            case "Enable" -> LifecycleHook.ON_ENABLE;
            case "Run" -> LifecycleHook.ON_RUN;
            case "Disable" -> LifecycleHook.ON_DISABLE;
            case "Unload" -> LifecycleHook.ON_UNLOAD;
            default -> null;
        };
    }

    private void inferFunctionReturnTypes(Map<String, FunctionNode> nodes,
                                          Map<String, ResolvedScript.ResolvedFunction> functions,
                                          Scope scriptScope) {
        for (int pass = 0; pass <= functions.size(); pass++) {
            boolean changed = false;
            for (var entry : functions.entrySet()) {
                ResolvedScript.ResolvedFunction function = entry.getValue();
                if (function.returnType() != null) continue;
                VeloraType inferred = inferReturnType(nodes.get(entry.getKey()).body(), scriptScope, function);
                if (inferred != null) {
                    function.returnType(inferred);
                    scriptScope.define(new Symbol(function.name(), inferred, Symbol.Kind.FUNCTION));
                    changed = true;
                }
            }
            if (!changed) break;
        }
        for (var entry : functions.entrySet()) {
            ResolvedScript.ResolvedFunction function = entry.getValue();
            if (function.returnType() != null) continue;
            FunctionNode node = nodes.get(entry.getKey());
            if (!containsValueReturn(node.body())) {
                function.returnType(VeloraTypes.UNIT);
            } else {
                error(DiagnosticCode.SEMANTIC_UNTYPED_DECLARATION,
                        "Cannot infer return type of function '" + function.name() + "'; add an explicit return type", node.line(), node.column());
                function.returnType(VeloraTypes.UNIT);
            }
        }
    }

    private VeloraType inferReturnType(BlockNode body, Scope parent, ResolvedScript.ResolvedFunction function) {
        Scope scope = new Scope(parent);
        for (ResolvedScript.ResolvedParam parameter : function.parameters()) scope.define(new Symbol(parameter.name(), parameter.type(), Symbol.Kind.PARAMETER));
        List<VeloraType> returns = new ArrayList<>();
        if (!inferBlockReturns(body, scope, function, returns)) return null;
        if (returns.isEmpty()) return VeloraTypes.UNIT;
        VeloraType result = returns.get(0);
        for (int i = 1; i < returns.size(); i++) {
            VeloraType common = commonType(result, returns.get(i));
            if (common == null) return null;
            result = common;
        }
        return result;
    }

    private boolean inferBlockReturns(BlockNode block, Scope parent, ResolvedScript.ResolvedFunction function, List<VeloraType> returns) {
        if (block == null) return true;
        Scope scope = new Scope(parent);
        for (StatementNode statement : block.statements()) {
            if (statement instanceof VariableDeclarationNode variable) {
                VeloraType type = variable.declaredType() != null ? resolveType(variable.declaredType(), variable) : inferExpressionType(variable.initializer(), scope, function);
                if (type != null) scope.define(new Symbol(variable.name(), type, Symbol.Kind.LOCAL));
            } else if (statement instanceof ExpressionStatementNode expression
                    && expression.expression() instanceof AssignmentExpressionNode assignment
                    && "=".equals(assignment.operator())
                    && assignment.target() instanceof IdentifierExpressionNode identifier
                    && scope.resolve(identifier.name()) == null) {
                VeloraType type = inferExpressionType(assignment.value(), scope, function);
                if (type != null) scope.define(new Symbol(identifier.name(), type, Symbol.Kind.LOCAL));
            } else if (statement instanceof ReturnStatementNode ret) {
                if (ret.value() == null) returns.add(VeloraTypes.UNIT);
                else {
                    VeloraType type = inferExpressionType(ret.value(), scope, function);
                    if (type == null || type == VeloraTypes.UNIT && ret.value() instanceof CallExpressionNode) return false;
                    returns.add(type);
                }
            } else if (statement instanceof IfStatementNode branch) {
                if (!inferBlockReturns(branch.thenBlock(), scope, function, returns) || !inferBlockReturns(branch.elseBlock(), scope, function, returns)) return false;
            } else if (statement instanceof WhileStatementNode loop) {
                if (!inferBlockReturns(loop.body(), scope, function, returns)) return false;
            } else if (statement instanceof ForStatementNode loop) {
                Scope loopScope = new Scope(scope);
                VeloraType iterable = inferExpressionType(loop.iterable(), scope, function);
                VeloraType element = iterable == null ? null : VeloraTypes.listElement(iterable.nonNull());
                if (element == null && iterable != null) element = VeloraTypes.setElement(iterable.nonNull());
                if (element != null) loopScope.define(new Symbol(loop.variable(), element, Symbol.Kind.LOCAL));
                if (!inferBlockReturns(loop.body(), loopScope, function, returns)) return false;
            } else if (statement instanceof WhenStatementNode when) {
                for (WhenStatementNode.Case item : when.cases()) if (!inferBlockReturns(item.body(), scope, function, returns)) return false;
                if (!inferBlockReturns(when.elseBody(), scope, function, returns)) return false;
            }
        }
        return true;
    }

    private void collectReturns(BlockNode block, List<ReturnStatementNode> out) {
        if (block == null) return;
        for (StatementNode statement : block.statements()) {
            if (statement instanceof ReturnStatementNode ret) out.add(ret);
            else if (statement instanceof IfStatementNode branch) {
                collectReturns(branch.thenBlock(), out);
                collectReturns(branch.elseBlock(), out);
            } else if (statement instanceof WhileStatementNode loop) collectReturns(loop.body(), out);
            else if (statement instanceof ForStatementNode loop) collectReturns(loop.body(), out);
            else if (statement instanceof WhenStatementNode when) {
                for (WhenStatementNode.Case item : when.cases()) collectReturns(item.body(), out);
                collectReturns(when.elseBody(), out);
            }
        }
    }

    private boolean containsValueReturn(BlockNode block) {
        List<ReturnStatementNode> returns = new ArrayList<>();
        collectReturns(block, returns);
        return returns.stream().anyMatch(value -> value.value() != null);
    }

    private VeloraType inferExpressionType(ExpressionNode expression, Scope scope, ResolvedScript.ResolvedFunction current) {
        if (expression == null) return VeloraTypes.UNIT;
        if (expression instanceof LiteralExpressionNode literal) return literalType(literal);
        if (expression instanceof IdentifierExpressionNode identifier) {
            Symbol symbol = scope.resolve(identifier.name());
            return symbol != null ? symbol.type() : null;
        }
        if (expression instanceof BinaryExpressionNode binary) {
            VeloraType left = inferExpressionType(binary.left(), scope, current);
            VeloraType right = inferExpressionType(binary.right(), scope, current);
            if (left == null || right == null) return null;
            if (binary.operator().equals("==") || binary.operator().equals("!=") || binary.operator().equals("<") || binary.operator().equals("<=") || binary.operator().equals(">") || binary.operator().equals(">=") || binary.operator().equals("&&") || binary.operator().equals("||")) return VeloraTypes.BOOLEAN;
            if (binary.operator().equals("+") && (left.nonNull() == VeloraTypes.STRING || right.nonNull() == VeloraTypes.STRING)) return VeloraTypes.STRING;
            return commonType(left, right);
        }
        if (expression instanceof UnaryExpressionNode unary) return unary.operator().equals("!") ? VeloraTypes.BOOLEAN : inferExpressionType(unary.operand(), scope, current);
        if (expression instanceof CallExpressionNode call) return inferCallType(call, scope, current);
        if (expression instanceof MemberAccessExpressionNode member) return inferMemberType(member, scope, current);
        if (expression instanceof ElvisExpressionNode elvis) {
            VeloraType left = inferExpressionType(elvis.left(), scope, current);
            VeloraType right = inferExpressionType(elvis.right(), scope, current);
            if (left == null) return right;
            if (right == null) return left.nonNull();
            return commonType(left.nonNull(), right);
        }
        if (expression instanceof IsExpressionNode) return VeloraTypes.BOOLEAN;
        if (expression instanceof ListLiteralExpressionNode list) {
            VeloraType element = null;
            for (ExpressionNode value : list.elements()) {
                VeloraType type = inferExpressionType(value, scope, current);
                if (type == null) return null;
                element = element == null ? type : commonType(element, type);
                if (element == null) return null;
            }
            return VeloraTypes.list(element == null ? VeloraTypes.UNIT : element);
        }
        if (expression instanceof MapLiteralExpressionNode map) {
            VeloraType key = null, value = null;
            for (var item : map.entries()) {
                VeloraType currentKey = inferExpressionType(item.getKey(), scope, current);
                VeloraType currentValue = inferExpressionType(item.getValue(), scope, current);
                if (currentKey == null || currentValue == null) return null;
                key = key == null ? currentKey : commonType(key, currentKey);
                value = value == null ? currentValue : commonType(value, currentValue);
                if (key == null || value == null) return null;
            }
            return VeloraTypes.map(key == null ? VeloraTypes.UNIT : key, value == null ? VeloraTypes.UNIT : value);
        }
        if (expression instanceof CollectionConstructorExpressionNode collection) return collectionType(collection);
        if (expression instanceof InterpolationExpressionNode) return VeloraTypes.STRING;
        if (expression instanceof DurationExpressionNode) return VeloraTypes.DURATION;
        if (expression instanceof IndexExpressionNode index) {
            VeloraType receiver = inferExpressionType(index.receiver(), scope, current);
            if (receiver == null) return null;
            VeloraType base = receiver.nonNull();
            VeloraType element = VeloraTypes.listElement(base);
            if (element != null) return element;
            VeloraType mapValue = VeloraTypes.mapValue(base);
            if (mapValue != null) return mapValue;
            if (base == VeloraTypes.STRING) return VeloraTypes.CHAR;
            return null;
        }
        if (expression instanceof SpawnExpressionNode spawn && spawn.callee() instanceof IdentifierExpressionNode id) {
            ResolvedScript.ResolvedFunction function = resolvedFunctions.get(id.name());
            return function != null && function.returnType() != null ? VeloraTypes.task(function.returnType()) : null;
        }
        return null;
    }

    private VeloraType inferCallType(CallExpressionNode call, Scope scope, ResolvedScript.ResolvedFunction current) {
        ExpressionNode callee = call.callee();
        if (callee instanceof IdentifierExpressionNode id) {
            if (id.name().equals("delay") || id.name().equals("yield")) return VeloraTypes.UNIT;
            if (id.name().equals("await") && call.arguments().size() == 1) {
                VeloraType task = inferExpressionType(call.arguments().get(0), scope, current);
                return task == null ? null : VeloraTypes.taskResult(task.nonNull());
            }
            ResolvedScript.ResolvedFunction function = resolvedFunctions != null ? resolvedFunctions.get(id.name()) : null;
            return function != null ? function.returnType() : null;
        }
        if (callee instanceof MemberAccessExpressionNode member) {
            if (member.target() instanceof IdentifierExpressionNode namespace && isApiNamespace(namespace.name())) {
                FunctionDescriptor descriptor = apiRegistry.find(resolveApiNamespace(namespace.name()), member.member());
                return descriptor != null ? descriptor.returnType() : null;
            }
            VeloraType receiver = inferExpressionType(member.target(), scope, current);
            if (receiver == null) return null;
            VeloraType base = receiver.nonNull();
            boolean list = VeloraTypes.listElement(base) != null;
            boolean set = VeloraTypes.setElement(base) != null;
            boolean map = VeloraTypes.mapKey(base) != null;
            if (!list && !set && !map) return null;
            return switch (member.member()) {
                case "contains", "containsKey", "remove" -> VeloraTypes.BOOLEAN;
                case "add", "put", "clear" -> VeloraTypes.UNIT;
                default -> null;
            };
        }
        return null;
    }

    private VeloraType inferMemberType(MemberAccessExpressionNode member, Scope scope, ResolvedScript.ResolvedFunction current) {
        if (member.target() instanceof IdentifierExpressionNode namespace) {
            ConstantRegistry.Constant constant = constantRegistry.find(namespace.name(), member.member());
            if (constant != null) return constant.type();
            if (isApiNamespace(namespace.name())) {
                FunctionDescriptor descriptor = apiRegistry.find(resolveApiNamespace(namespace.name()), member.member());
                if (descriptor != null && descriptor.parameters().isEmpty()) return descriptor.returnType();
            }
        }
        VeloraType receiver = inferExpressionType(member.target(), scope, current);
        if (receiver == null) return null;
        VeloraType base = receiver.nonNull();
        if (base instanceof io.velora.api.type.StructType struct && struct.hasProperty(member.member())) return struct.property(member.member()).type();
        if (base == VeloraTypes.STRING && member.member().equals("length")) return VeloraTypes.INT;
        if (isCollection(base) && member.member().equals("size")) return VeloraTypes.INT;
        if (isCollection(base) && member.member().equals("isEmpty")) return VeloraTypes.BOOLEAN;
        return null;
    }

    private enum FunctionRoleKind { NORMAL, LIFECYCLE, EVENT }

    private record FunctionRole(FunctionRoleKind kind, String annotation, LifecycleHook lifecycle, EventDescriptor event) {
        static FunctionRole normal() { return new FunctionRole(FunctionRoleKind.NORMAL, null, null, null); }
        static FunctionRole lifecycle(String annotation, LifecycleHook hook) { return new FunctionRole(FunctionRoleKind.LIFECYCLE, annotation, hook, null); }
        static FunctionRole event(String annotation, EventDescriptor event) { return new FunctionRole(FunctionRoleKind.EVENT, annotation, null, event); }
    }


    private boolean eventPayloadAssignable(VeloraType payload, VeloraType parameter) {
        if (payload == null || parameter == null) return false;
        if (!payload.nonNull().name().equals(parameter.nonNull().name())) return false;
        return !payload.isNullable() || parameter.isNullable();
    }

    private void checkFunction(ResolvedScript.ResolvedFunction rf, Scope parentScope) {
        Scope fnScope = new Scope(parentScope);
        for (ResolvedScript.ResolvedParam p : rf.parameters()) {
            fnScope.define(new Symbol(p.name(), p.type(), Symbol.Kind.PARAMETER));
        }
        if (rf.body() != null) {
            checkBlock(rf.body(), fnScope, rf);
            if (rf.returnType() != VeloraTypes.UNIT && !rf.isLifecycle() && !blockAlwaysReturns(rf.body())) {
                error(DiagnosticCode.SEMANTIC_MISSING_RETURN, "Not every path returns a value in function " + rf.name(), rf.body().line(), rf.body().column());
            }
        }
    }

    private boolean blockAlwaysReturns(BlockNode block) {
        if (block == null) return false;
        for (StatementNode stmt : block.statements()) {
            if (statementAlwaysReturns(stmt)) return true;
        }
        return false;
    }

    private boolean statementAlwaysReturns(StatementNode stmt) {
        if (stmt instanceof ReturnStatementNode) return true;
        if (stmt instanceof IfStatementNode iff) {
            return iff.thenBlock() != null && iff.elseBlock() != null
                    && blockAlwaysReturns(iff.thenBlock()) && blockAlwaysReturns(iff.elseBlock());
        }
        if (stmt instanceof WhenStatementNode when) {
            if (when.elseBody() == null || !blockAlwaysReturns(when.elseBody())) return false;
            for (WhenStatementNode.Case c : when.cases()) {
                if (!blockAlwaysReturns(c.body())) return false;
            }
            return true;
        }
        return false;
    }

    private boolean isPrimitiveType(VeloraType type) {
        return type == VeloraTypes.INT || type == VeloraTypes.LONG || type == VeloraTypes.DOUBLE
            || type == VeloraTypes.FLOAT || type == VeloraTypes.BOOLEAN || type == VeloraTypes.BYTE
            || type == VeloraTypes.CHAR;
    }

    private void checkBlock(BlockNode block, Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        Scope blockScope = new Scope(scope);
        for (StatementNode stmt : block.statements()) {
            checkStatement(stmt, blockScope, currentFn);
        }
    }

    private void checkStatement(StatementNode stmt, Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        if (stmt instanceof VariableDeclarationNode vd) {
            VeloraType type = vd.declaredType() != null ? resolveType(vd.declaredType(), vd) : null;
            if (scope.definesLocally(vd.name())) {
                error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate local variable: " + vd.name(), vd.line(), vd.column());
            }
            if (vd.initializer() == null) {
                error(DiagnosticCode.SEMANTIC_MISSING_INITIALIZER, "Local variable '" + vd.name() + "' requires an initializer", vd.line(), vd.column());
            } else {
                VeloraType initType = checkExpression(vd.initializer(), scope, currentFn);
                if (type == null) type = initType;
                else if (initType != null && initType != VeloraTypes.UNIT && !isAssignableExpression(vd.initializer(), initType, type)) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Variable '" + vd.name() + "' expects " + type.name() + ", got " + initType.name(), vd.line(), vd.column());
                }
            }
            if (type == null) type = VeloraTypes.UNIT;
            if (vd.initializer() instanceof LiteralExpressionNode lit && lit.kind() == LiteralExpressionNode.LiteralKind.NULL
                    && isPrimitiveType(type)) {
                error(DiagnosticCode.SEMANTIC_PRIMITIVE_NULL, "Cannot assign null to primitive type " + type.name(), vd.line(), vd.column());
            }
            scope.define(new Symbol(vd.name(), type, Symbol.Kind.LOCAL));
        } else if (stmt instanceof IfStatementNode iff) {
            VeloraType condType = checkExpression(iff.condition(), scope, currentFn);
            if (condType != null && condType != VeloraTypes.BOOLEAN && condType != VeloraTypes.UNIT && !condType.name().equals("Boolean?")) {
                error(DiagnosticCode.SEMANTIC_NON_BOOLEAN_CONDITION, "If condition must be boolean, got " + condType.name(), iff.line(), iff.column());
            }
            if (iff.thenBlock() != null) checkBlock(iff.thenBlock(), scope, currentFn);
            if (iff.elseBlock() != null) checkBlock(iff.elseBlock(), scope, currentFn);
        } else if (stmt instanceof WhileStatementNode ws) {
            VeloraType condType = checkExpression(ws.condition(), scope, currentFn);
            if (condType != null && condType != VeloraTypes.BOOLEAN && condType != VeloraTypes.UNIT && !condType.name().equals("Boolean?")) {
                error(DiagnosticCode.SEMANTIC_NON_BOOLEAN_CONDITION, "While condition must be boolean, got " + condType.name(), ws.line(), ws.column());
            }
            if (ws.body() != null) checkBlock(ws.body(), scope, currentFn);
        } else if (stmt instanceof ForStatementNode fs) {
            VeloraType iterType = checkExpression(fs.iterable(), scope, currentFn);
            VeloraType baseType = iterType == null ? null : iterType.nonNull();
            VeloraType elemType = baseType == null ? null : VeloraTypes.listElement(baseType);
            if (elemType == null && baseType != null) elemType = VeloraTypes.setElement(baseType);
            if (iterType != null && elemType == null && iterType != VeloraTypes.UNIT) {
                error(DiagnosticCode.SEMANTIC_NON_ITERABLE, "For loop iterable must be a List or Set, got " + iterType.name(), fs.line(), fs.column());
            }
            if (elemType == null) elemType = VeloraTypes.UNIT;
            VeloraType declaredType = resolveType(fs.variableType(), fs);
            if (declaredType != null && elemType != VeloraTypes.UNIT && !isAssignable(elemType, declaredType)) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Loop variable '" + fs.variable() + "' expects " + declaredType.name() + ", got " + elemType.name(), fs.line(), fs.column());
            }
            Scope forScope = new Scope(scope);
            forScope.define(new Symbol(fs.variable(), declaredType != null ? declaredType : elemType, Symbol.Kind.LOCAL));
            if (fs.body() != null) checkBlock(fs.body(), forScope, currentFn);
        } else if (stmt instanceof WhenStatementNode ws) {
            VeloraType subjType = checkExpression(ws.subject(), scope, currentFn);
            for (WhenStatementNode.Case c : ws.cases()) {
                for (ExpressionNode cond : c.conditions()) {
                    VeloraType condType = checkExpression(cond, scope, currentFn);
                    if (subjType != VeloraTypes.UNIT && condType != VeloraTypes.UNIT
                            && !isAssignable(condType, subjType) && !isAssignable(subjType, condType)) {
                        error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "when case type " + condType.name() + " is incompatible with " + subjType.name(), cond.line(), cond.column());
                    }
                }
                if (c.body() != null) checkBlock(c.body(), scope, currentFn);
            }
            if (ws.elseBody() != null) checkBlock(ws.elseBody(), scope, currentFn);
        } else if (stmt instanceof ReturnStatementNode rs) {
            if (rs.value() == null) {
                if (currentFn.returnType() != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_WRONG_RETURN_TYPE, "Function returning " + currentFn.returnType().name() + " requires a return value", rs.line(), rs.column());
                }
            } else {
                VeloraType retExprType = checkExpression(rs.value(), scope, currentFn);
                // Check Unit-returning function returning a value
                if (currentFn.returnType() == VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_VOID_RETURN_VALUE, "Void function cannot return a value", rs.line(), rs.column());
                }
                // Check return type mismatch (only for non-void, non-unit return types)
                if (currentFn.returnType() != null && currentFn.returnType() != VeloraTypes.UNIT && retExprType != null && retExprType != VeloraTypes.UNIT) {
                    if (!isAssignableExpression(rs.value(), retExprType, currentFn.returnType())) {
                        error(DiagnosticCode.SEMANTIC_WRONG_RETURN_TYPE, "Return type mismatch: expected " + currentFn.returnType().name() + ", got " + retExprType.name(), rs.line(), rs.column());
                    }
                }
            }
        } else if (stmt instanceof ExpressionStatementNode es) {
            if (es.expression() instanceof AssignmentExpressionNode assignment
                    && assignment.operator().equals("=")
                    && assignment.target() instanceof IdentifierExpressionNode identifier
                    && scope.resolve(identifier.name()) == null) {
                VeloraType valueType = checkExpression(assignment.value(), scope, currentFn);
                if (valueType == null || valueType == VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_UNTYPED_DECLARATION, "Cannot infer type of local '" + identifier.name() + "'", identifier.line(), identifier.column());
                    valueType = VeloraTypes.UNIT;
                }
                scope.define(new Symbol(identifier.name(), valueType, Symbol.Kind.LOCAL));
            } else {
                checkExpression(es.expression(), scope, currentFn);
            }
        }
    }

    private boolean isAssignable(VeloraType from, VeloraType to) {
        if (from == null || to == null) return false;
        if (from == to || to == VeloraTypes.UNIT) return true;
        if (isNullType(from)) return !to.isPrimitive() || to.isNullable();
        if (VeloraTypes.isWidening(from, to)) return true;
        if (from.name().equals(to.name())) return true;
        return to.isNullable() && from.nonNull().name().equals(to.nonNull().name());
    }

    private boolean isAssignableExpression(ExpressionNode expression, VeloraType from, VeloraType to) {
        if (isAssignable(from, to)) return true;
        VeloraType target = to != null ? to.nonNull() : null;
        if (expression instanceof ListLiteralExpressionNode list && list.elements().isEmpty()) return VeloraTypes.listElement(target) != null;
        if (expression instanceof MapLiteralExpressionNode map && map.entries().isEmpty()) return VeloraTypes.mapKey(target) != null;
        return false;
    }

    private VeloraType checkExpression(ExpressionNode expr, Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        if (expr == null) return VeloraTypes.UNIT;
        if (expr instanceof LiteralExpressionNode lit) {
            return literalType(lit);
        }
        if (expr instanceof IdentifierExpressionNode id) {
            Symbol s = scope.resolve(id.name());
            if (s == null) {
                // Could be an API namespace
                if (isApiNamespace(id.name())) {
                    return VeloraTypes.UNIT; // namespace itself
                }
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unresolved symbol: " + id.name(), id.line(), id.column());
                return VeloraTypes.UNIT;
            }
            return s.type();
        }
        if (expr instanceof BinaryExpressionNode bin) {
            VeloraType lt = checkExpression(bin.left(), scope, currentFn);
            VeloraType rt = checkExpression(bin.right(), scope, currentFn);
            return checkBinary(bin, lt, rt);
        }
        if (expr instanceof UnaryExpressionNode un) {
            VeloraType operandType = checkExpression(un.operand(), scope, currentFn);
            if (un.operator().equals("!")) {
                if (operandType != VeloraTypes.BOOLEAN && operandType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Operator ! requires Boolean, got " + operandType.name(), un.line(), un.column());
                }
                return VeloraTypes.BOOLEAN;
            }
            if ((un.operator().equals("+") || un.operator().equals("-")) && !isNumeric(operandType) && operandType != VeloraTypes.UNIT) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Unary " + un.operator() + " requires a number, got " + operandType.name(), un.line(), un.column());
            }
            return operandType;
        }
        if (expr instanceof CallExpressionNode call) {
            return checkCall(call, scope, currentFn);
        }
        if (expr instanceof MemberAccessExpressionNode mem) {
            if (mem.target() instanceof IdentifierExpressionNode namespace) {
                ConstantRegistry.Constant constant = constantRegistry.find(namespace.name(), mem.member());
                if (constant != null) return constant.type();
                VeloraType enumType = typeRegistry.find(namespace.name());
                if (enumType instanceof io.velora.api.type.EnumType registeredEnum && registeredEnum.hasConstant(mem.member())) return registeredEnum;
            }
            VeloraType recvType = checkExpression(mem.target(), scope, currentFn);
            VeloraType baseType = recvType != null ? recvType.nonNull() : VeloraTypes.UNIT;
            VeloraType memberType = null;
            if (baseType instanceof io.velora.api.type.StructType st && st.hasProperty(mem.member())) memberType = st.property(mem.member()).type();
            else if (baseType == VeloraTypes.STRING && mem.member().equals("length")) memberType = VeloraTypes.INT;
            else if (isCollection(baseType) && mem.member().equals("size")) memberType = VeloraTypes.INT;
            else if (isCollection(baseType) && mem.member().equals("isEmpty")) memberType = VeloraTypes.BOOLEAN;
            else if ((baseType == VeloraTypes.VEC2 || baseType == VeloraTypes.VEC3 || baseType == VeloraTypes.COLOR) && mem.member().equals("size")) memberType = VeloraTypes.INT;
            else if (baseType == VeloraTypes.VEC2 && (mem.member().equals("x") || mem.member().equals("y"))) memberType = VeloraTypes.DOUBLE;
            else if (baseType == VeloraTypes.VEC3 && (mem.member().equals("x") || mem.member().equals("y") || mem.member().equals("z"))) memberType = VeloraTypes.DOUBLE;
            else if (baseType == VeloraTypes.COLOR && (mem.member().equals("r") || mem.member().equals("g") || mem.member().equals("b") || mem.member().equals("a"))) memberType = VeloraTypes.INT;
            if (memberType != null) {
                if (recvType != null && recvType.isNullable() && !mem.isSafeAccess()) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Nullable value requires safe member access (?.)", mem.line(), mem.column());
                }
                return mem.isSafeAccess() ? memberType.nullable() : memberType;
            }
            if (mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
                FunctionDescriptor fd = apiRegistry.find(resolveApiNamespace(ns.name()), mem.member());
                if (fd != null && fd.parameters().isEmpty()) {
                    return fd.returnType();
                }
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown API property: " + ns.name() + "." + mem.member(), mem.line(), mem.column());
                return VeloraTypes.UNIT;
            }
            if (recvType != VeloraTypes.UNIT) error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown member '" + mem.member() + "' on " + baseType.name(), mem.line(), mem.column());
            return VeloraTypes.UNIT;
        }
        if (expr instanceof QualifiedExpressionNode q) {
            ConstantRegistry.Constant c = constantRegistry.find(q.qualifier(), q.member());
            if (c != null) return c.type();
            // Could be enum constant
            VeloraType enumType = typeRegistry.find(q.qualifier());
            if (enumType instanceof io.velora.api.type.EnumType et && et.hasConstant(q.member())) {
                return et;
            }
            error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unresolved constant: " + q.qualifier() + "." + q.member(), q.line(), q.column());
            return VeloraTypes.UNIT;
        }
        if (expr instanceof ElvisExpressionNode el) {
            VeloraType left = checkExpression(el.left(), scope, currentFn);
            VeloraType right = checkExpression(el.right(), scope, currentFn);
            if (left == VeloraTypes.UNIT || right == VeloraTypes.UNIT) return right;
            if (!left.isNullable()) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Elvis operator requires a nullable left operand, got " + left.name(), el.line(), el.column());
                return left;
            }
            if (isNullType(left)) return right;
            VeloraType result = commonType(left.nonNull(), right);
            if (result == null) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Elvis operands are incompatible: " + left.name() + " and " + right.name(), el.line(), el.column());
                return left.nonNull();
            }
            return result;
        }
        if (expr instanceof IsExpressionNode is) {
            VeloraType operandType = checkExpression(is.operand(), scope, currentFn);
            VeloraType targetType = resolveType(is.type(), is);
            if (operandType != VeloraTypes.UNIT && targetType != null
                    && !operandType.nonNull().name().equals(targetType.nonNull().name())) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Cannot test " + operandType.name() + " against unrelated type " + targetType.name(), is.line(), is.column());
            }
            return VeloraTypes.BOOLEAN;
        }
        if (expr instanceof ListLiteralExpressionNode list) {
            VeloraType elem = null;
            for (ExpressionNode e : list.elements()) {
                VeloraType current = checkExpression(e, scope, currentFn);
                if (elem == null || elem == VeloraTypes.UNIT) elem = current;
                else if (current != VeloraTypes.UNIT) {
                    VeloraType common = commonType(elem, current);
                    if (common == null) {
                        error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "List elements must have compatible types, got " + elem.name() + " and " + current.name(), e.line(), e.column());
                    } else elem = common;
                }
            }
            return VeloraTypes.list(elem == null ? VeloraTypes.UNIT : elem);
        }
        if (expr instanceof MapLiteralExpressionNode map) {
            VeloraType key = null;
            VeloraType value = null;
            for (var entry : map.entries()) {
                VeloraType currentKey = checkExpression(entry.getKey(), scope, currentFn);
                VeloraType currentValue = checkExpression(entry.getValue(), scope, currentFn);
                key = mergeLiteralType(key, currentKey, entry.getKey(), "Map keys");
                value = mergeLiteralType(value, currentValue, entry.getValue(), "Map values");
            }
            if (key == null) key = VeloraTypes.UNIT;
            if (value == null) value = VeloraTypes.UNIT;
            if (key != VeloraTypes.UNIT && !key.isHashable()) {
                error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Map key type must be hashable, got " + key.name(), map.line(), map.column());
            }
            return VeloraTypes.map(key, value);
        }
        if (expr instanceof CollectionConstructorExpressionNode collection) {
            return collectionType(collection);
        }
        if (expr instanceof InterpolationExpressionNode interp) {
            for (InterpolationExpressionNode.Segment seg : interp.segments()) {
                if (seg instanceof InterpolationExpressionNode.Expr e) {
                    checkExpression(e.expression(), scope, currentFn);
                }
            }
            return VeloraTypes.STRING;
        }
        if (expr instanceof DurationExpressionNode dur) {
            checkExpression(dur.amount(), scope, currentFn);
            return VeloraTypes.DURATION;
        }
        if (expr instanceof IndexExpressionNode idx) {
            VeloraType recvType = checkExpression(idx.receiver(), scope, currentFn);
            VeloraType indexType = checkExpression(idx.index(), scope, currentFn);
            if (recvType != null && recvType.isNullable()) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Cannot index a nullable value", idx.line(), idx.column());
            }
            VeloraType baseType = recvType != null ? recvType.nonNull() : VeloraTypes.UNIT;
            VeloraType elementType = VeloraTypes.listElement(baseType);
            if (elementType != null) {
                if (indexType != null && indexType != VeloraTypes.INT && indexType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "List index must be Int, got " + indexType.name(), idx.line(), idx.column());
                }
                return elementType;
            }
            if (baseType == VeloraTypes.VEC2 || baseType == VeloraTypes.VEC3 || baseType == VeloraTypes.COLOR) {
                if (indexType != null && indexType != VeloraTypes.INT && indexType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, baseType.name() + " index must be Int, got " + indexType.name(), idx.line(), idx.column());
                }
                return baseType == VeloraTypes.COLOR ? VeloraTypes.INT : VeloraTypes.DOUBLE;
            }
            if (baseType == VeloraTypes.STRING) {
                if (indexType != null && indexType != VeloraTypes.INT && indexType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "String index must be Int, got " + indexType.name(), idx.line(), idx.column());
                }
                return VeloraTypes.CHAR;
            }
            VeloraType keyType = VeloraTypes.mapKey(baseType);
            if (keyType != null) {
                if (indexType != null && indexType != VeloraTypes.UNIT && !isAssignable(indexType, keyType)) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "Map key type mismatch: expected " + keyType.name() + ", got " + indexType.name(), idx.line(), idx.column());
                }
                VeloraType valueType = VeloraTypes.mapValue(recvType);
                return valueType == null ? VeloraTypes.UNIT : valueType;
            }
            error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Type " + baseType.name() + " is not indexable", idx.line(), idx.column());
            return VeloraTypes.UNIT;
        }
        if (expr instanceof AssignmentExpressionNode assign) {
            VeloraType targetType = checkExpression(assign.target(), scope, currentFn);
            if (assign.target() instanceof IdentifierExpressionNode id) {
                Symbol symbol = scope.resolve(id.name());
                if (symbol != null && symbol.isConstProperty()) error(DiagnosticCode.SEMANTIC_CONST_ASSIGNMENT, "Cannot assign to constant '" + id.name() + "'", id.line(), id.column());
                if (symbol != null && symbol.isSetting()) error(DiagnosticCode.SEMANTIC_SETTING_WRITE, "Cannot assign to setting '" + id.name() + "' (settings are read-only)", id.line(), id.column());
            } else {
                error(DiagnosticCode.SEMANTIC_INVALID_ASSIGNMENT_TARGET, "Only variables and script fields are assignable", assign.target().line(), assign.target().column());
            }
            if (assign.operator().equals("++") || assign.operator().equals("--")) {
                if (!isNumeric(targetType) && targetType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Operator " + assign.operator() + " requires a numeric target, got " + targetType.name(), assign.line(), assign.column());
                }
                return targetType;
            }
            VeloraType valueType = assign.value() == null ? VeloraTypes.UNIT : checkExpression(assign.value(), scope, currentFn);
            if (assign.operator().equals("=")) {
                if (targetType != VeloraTypes.UNIT && valueType != VeloraTypes.UNIT && !isAssignableExpression(assign.value(), valueType, targetType)) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Assignment type mismatch: expected " + targetType.name() + ", got " + valueType.name(), assign.line(), assign.column());
                }
            } else {
                String binaryOperator = assign.operator().substring(0, 1);
                VeloraType result = checkBinary(binaryOperator, targetType, valueType, assign.line(), assign.column());
                if (targetType != VeloraTypes.UNIT && result != VeloraTypes.UNIT && !isAssignable(result, targetType)) {
                    error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Compound assignment result " + result.name() + " cannot be stored in " + targetType.name(), assign.line(), assign.column());
                }
            }
            return targetType;
        }
        if (expr instanceof SpawnExpressionNode spawn) {
            if (!(spawn.callee() instanceof IdentifierExpressionNode id)) {
                checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn);
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "spawn requires a script function", spawn.line(), spawn.column());
                return VeloraTypes.task(VeloraTypes.UNIT);
            }
            Symbol symbol = scope.resolve(id.name());
            if (symbol == null || !symbol.isFunction()) {
                checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn);
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "spawn requires a script function", spawn.line(), spawn.column());
                return VeloraTypes.task(VeloraTypes.UNIT);
            }
            VeloraType resultType = checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn);
            return VeloraTypes.task(resultType == null ? VeloraTypes.UNIT : resultType);
        }
        return VeloraTypes.UNIT;
    }

    private VeloraType checkCall(CallExpressionNode call, Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        return checkCallExpression(call.callee(), call.arguments(), scope, currentFn);
    }

    private VeloraType checkCallExpression(ExpressionNode callee, List<ExpressionNode> args, Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        // Resolve callee: could be member access (namespace.function) or identifier (user function)
        if (callee instanceof MemberAccessExpressionNode mem) {
            if (mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
                // API call: namespace.function(args)
                FunctionDescriptor fd = apiRegistry.find(resolveApiNamespace(ns.name()), mem.member());
                if (fd != null) {
                    List<ExpressionNode> bound = bindArguments(fd.qualifiedName(), args, fd.parameters().stream().map(io.velora.api.function.ParameterDescriptor::name).toList(), fd.parameters().stream().map(io.velora.api.function.ParameterDescriptor::hasDefault).toList(), scope, currentFn, mem.line(), mem.column());
                    for (int i = 0; i < bound.size(); i++) {
                        ExpressionNode argExpr = bound.get(i);
                        if (argExpr == null) continue;
                        VeloraType argType = checkExpression(argExpr, scope, currentFn);
                        VeloraType paramType = fd.parameters().get(i).type();
                        if (argType != null && argType != VeloraTypes.UNIT && !isAssignableExpression(argExpr, argType, paramType)) {
                            error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Argument '" + fd.parameters().get(i).name() + "' type mismatch: expected " + paramType.name() + ", got " + argType.name(), argExpr.line(), argExpr.column());
                        }
                    }
                    if (fd.suspending() && !currentFn.suspending()) {
                        error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call async API '" + fd.qualifiedName() + "'", mem.line(), mem.column());
                    }
                    return fd.returnType();
                }
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown API function: " + ns.name() + "." + mem.member(), mem.line(), mem.column());
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
                return VeloraTypes.UNIT;
            }
            VeloraType receiverType = checkExpression(mem.target(), scope, currentFn);
            VeloraType collectionResult = checkCollectionMethod(mem, receiverType, args, scope, currentFn);
            if (collectionResult != null) return collectionResult;
            for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
            error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown method: " + mem.member(), mem.line(), mem.column());
            return VeloraTypes.UNIT;
        }
        if (callee instanceof IdentifierExpressionNode id) {
            Symbol s = scope.resolve(id.name());
            if (s != null && s.isFunction()) {
                if (resolvedFunctions != null) {
                    ResolvedScript.ResolvedFunction calledFn = resolvedFunctions.get(id.name());
                    if (calledFn != null) {
                        // Check async violation
                        if (calledFn.suspending() && !currentFn.suspending()) {
                            error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call async function '" + id.name() + "'", id.line(), id.column());
                        }
                        List<ExpressionNode> bound = bindArguments(id.name(), args, calledFn.parameters().stream().map(ResolvedScript.ResolvedParam::name).toList(), calledFn.parameters().stream().map(ResolvedScript.ResolvedParam::hasDefault).toList(), scope, currentFn, id.line(), id.column());
                        for (int i = 0; i < bound.size(); i++) {
                            ExpressionNode argExpr = bound.get(i);
                            if (argExpr == null) continue;
                            VeloraType argType = checkExpression(argExpr, scope, currentFn);
                            VeloraType paramType = calledFn.parameters().get(i).type();
                            if (argType != null && argType != VeloraTypes.UNIT && !isAssignableExpression(argExpr, argType, paramType)) {
                                error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Argument '" + calledFn.parameters().get(i).name() + "' type mismatch: expected " + paramType.name() + ", got " + argType.name(), argExpr.line(), argExpr.column());
                            }
                        }
                    }
                }
                return s.type();
            }
            if (id.name().equals("delay")) {
                if (!currentFn.suspending()) error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call delay()", id.line(), id.column());
                if (args.size() != 1) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "delay expects exactly one argument", id.line(), id.column());
                    for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
                    return VeloraTypes.UNIT;
                }
                VeloraType delayType = checkExpression(args.get(0), scope, currentFn);
                if (delayType != VeloraTypes.DURATION && !isNumeric(delayType) && delayType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "delay expects Duration or a nanosecond number, got " + delayType.name(), args.get(0).line(), args.get(0).column());
                }
                return VeloraTypes.UNIT;
            }
            if (id.name().equals("yield")) {
                if (!currentFn.suspending()) error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call yield()", id.line(), id.column());
                if (!args.isEmpty()) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "yield expects no arguments", id.line(), id.column());
                    for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
                }
                return VeloraTypes.UNIT;
            }
            if (id.name().equals("await")) {
                if (!currentFn.suspending()) error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call await()", id.line(), id.column());
                if (args.size() != 1) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "await expects exactly one Task", id.line(), id.column());
                    for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
                    return VeloraTypes.UNIT;
                }
                VeloraType taskType = checkExpression(args.get(0), scope, currentFn);
                VeloraType resultType = VeloraTypes.taskResult(taskType == null ? VeloraTypes.UNIT : taskType.nonNull());
                if (resultType == null) {
                    if (taskType != VeloraTypes.UNIT) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "await expects Task<T>, got " + taskType.name(), args.get(0).line(), args.get(0).column());
                    return VeloraTypes.UNIT;
                }
                return resultType;
            }
            if (id.name().equals("spawn")) {
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Use the spawn keyword before a script function call", id.line(), id.column());
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
                return VeloraTypes.UNIT;
            }
            error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unresolved function: " + id.name(), id.line(), id.column());
            for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
            return VeloraTypes.UNIT;
        }
        checkExpression(callee, scope, currentFn);
        for (ExpressionNode a : args) checkExpression(a, scope, currentFn);
        return VeloraTypes.UNIT;
    }

    private VeloraType collectionType(CollectionConstructorExpressionNode collection) {
        List<TypeNode> arguments = collection.typeArguments();
        int expected = collection.kind() == CollectionConstructorExpressionNode.Kind.MAP ? 2 : 1;
        if (arguments.size() != expected) {
            error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH,
                    collection.kind().name().toLowerCase() + " requires " + expected + " type argument" + (expected == 1 ? "" : "s"),
                    collection.line(), collection.column());
            return VeloraTypes.UNIT;
        }
        VeloraType first = resolveType(arguments.get(0), collection);
        if (first == null) return VeloraTypes.UNIT;
        return switch (collection.kind()) {
            case LIST -> VeloraTypes.list(first);
            case SET -> {
                if (!first.isHashable()) error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Set element type must be hashable, got " + first.name(), collection.line(), collection.column());
                yield VeloraTypes.set(first);
            }
            case MAP -> {
                VeloraType second = resolveType(arguments.get(1), collection);
                if (second == null) yield VeloraTypes.UNIT;
                if (!first.isHashable()) error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Map key type must be hashable, got " + first.name(), collection.line(), collection.column());
                yield VeloraTypes.map(first, second);
            }
        };
    }

    private boolean isCollection(VeloraType type) {
        if (type == null) return false;
        VeloraType base = type.nonNull();
        return VeloraTypes.listElement(base) != null || VeloraTypes.setElement(base) != null || VeloraTypes.mapKey(base) != null;
    }

    private VeloraType checkCollectionMethod(MemberAccessExpressionNode member, VeloraType receiverType,
                                             List<ExpressionNode> args, Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        if (receiverType == null) return null;
        VeloraType base = receiverType.nonNull();
        VeloraType listElement = VeloraTypes.listElement(base);
        VeloraType setElement = VeloraTypes.setElement(base);
        VeloraType mapKey = VeloraTypes.mapKey(base);
        VeloraType mapValue = VeloraTypes.mapValue(base);
        if (listElement == null && setElement == null && mapKey == null) return null;
        if (receiverType.isNullable()) error(DiagnosticCode.SEMANTIC_NULLABILITY, "Collection method requires a non-null receiver", member.line(), member.column());
        VeloraType element = listElement != null ? listElement : setElement;
        return switch (member.member()) {
            case "add" -> {
                if (mapKey != null) {
                    error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Map does not support add(); use put(key, value)", member.line(), member.column());
                    for (ExpressionNode arg : args) checkExpression(arg, scope, currentFn);
                } else {
                    checkCollectionArgs(member, args, List.of(element), scope, currentFn);
                }
                yield VeloraTypes.UNIT;
            }
            case "remove" -> {
                checkCollectionArgs(member, args, List.of(mapKey != null ? mapKey : element), scope, currentFn);
                yield VeloraTypes.BOOLEAN;
            }
            case "contains" -> {
                if (mapKey != null) {
                    error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Map does not support contains(); use containsKey(key)", member.line(), member.column());
                    for (ExpressionNode arg : args) checkExpression(arg, scope, currentFn);
                } else {
                    checkCollectionArgs(member, args, List.of(element), scope, currentFn);
                }
                yield VeloraTypes.BOOLEAN;
            }
            case "containsKey" -> {
                if (mapKey == null) {
                    error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "containsKey() is only available on Map", member.line(), member.column());
                    for (ExpressionNode arg : args) checkExpression(arg, scope, currentFn);
                } else checkCollectionArgs(member, args, List.of(mapKey), scope, currentFn);
                yield VeloraTypes.BOOLEAN;
            }
            case "put" -> {
                if (mapKey == null) {
                    error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "put() is only available on Map", member.line(), member.column());
                    for (ExpressionNode arg : args) checkExpression(arg, scope, currentFn);
                } else checkCollectionArgs(member, args, List.of(mapKey, mapValue), scope, currentFn);
                yield VeloraTypes.UNIT;
            }
            case "clear" -> {
                checkCollectionArgs(member, args, List.of(), scope, currentFn);
                yield VeloraTypes.UNIT;
            }
            default -> null;
        };
    }

    private void checkCollectionArgs(MemberAccessExpressionNode member, List<ExpressionNode> args, List<VeloraType> expected,
                                     Scope scope, ResolvedScript.ResolvedFunction currentFn) {
        if (args.size() != expected.size()) {
            error(DiagnosticCode.SEMANTIC_WRONG_ARITY, member.member() + " expects " + expected.size() + " argument(s), got " + args.size(), member.line(), member.column());
        }
        for (int i = 0; i < args.size(); i++) {
            VeloraType actual = checkExpression(args.get(i), scope, currentFn);
            if (i < expected.size() && actual != null && actual != VeloraTypes.UNIT && !isAssignableExpression(args.get(i), actual, expected.get(i))) {
                error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Argument " + (i + 1) + " of " + member.member() + " expects " + expected.get(i).name() + ", got " + actual.name(), args.get(i).line(), args.get(i).column());
            }
        }
    }

    private VeloraType literalType(LiteralExpressionNode lit) {
        return switch (lit.kind()) {
            case INTEGER -> VeloraTypes.INT;
            case LONG -> VeloraTypes.LONG;
            case FLOAT -> VeloraTypes.FLOAT;
            case DOUBLE -> VeloraTypes.DOUBLE;
            case STRING -> VeloraTypes.STRING;
            case BOOLEAN -> VeloraTypes.BOOLEAN;
            case NULL -> VeloraTypes.UNIT.nullable();
        };
    }

    private VeloraType checkBinary(BinaryExpressionNode expression, VeloraType left, VeloraType right) {
        return checkBinary(expression.operator(), left, right, expression.line(), expression.column());
    }

    private VeloraType checkBinary(String operator, VeloraType left, VeloraType right, int line, int column) {
        if (operator.equals("&&") || operator.equals("||")) {
            if ((left != VeloraTypes.BOOLEAN && left != VeloraTypes.UNIT) || (right != VeloraTypes.BOOLEAN && right != VeloraTypes.UNIT)) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Operator " + operator + " requires Boolean operands", line, column);
            }
            return VeloraTypes.BOOLEAN;
        }
        if (operator.equals("==") || operator.equals("!=")) {
            if (!areComparable(left, right)) error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Cannot compare " + left.name() + " and " + right.name(), line, column);
            return VeloraTypes.BOOLEAN;
        }
        if (operator.equals("<") || operator.equals("<=") || operator.equals(">") || operator.equals(">=")) {
            boolean valid = isNumeric(left) && isNumeric(right) || left == VeloraTypes.STRING && right == VeloraTypes.STRING;
            if (!valid && left != VeloraTypes.UNIT && right != VeloraTypes.UNIT) error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Operator " + operator + " requires numeric operands or two Strings", line, column);
            return VeloraTypes.BOOLEAN;
        }
        if (operator.equals("+") && (left == VeloraTypes.STRING || right == VeloraTypes.STRING)) return VeloraTypes.STRING;
        if (operator.equals("+") || operator.equals("-") || operator.equals("*") || operator.equals("/") || operator.equals("%")) {
            if ((!isNumeric(left) || !isNumeric(right)) && left != VeloraTypes.UNIT && right != VeloraTypes.UNIT) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Operator " + operator + " requires numeric operands, got " + left.name() + " and " + right.name(), line, column);
                return VeloraTypes.UNIT;
            }
            return commonNumericType(left, right);
        }
        return VeloraTypes.UNIT;
    }

    private boolean isNumeric(VeloraType type) {
        return type == VeloraTypes.BYTE || type == VeloraTypes.INT || type == VeloraTypes.LONG || type == VeloraTypes.FLOAT || type == VeloraTypes.DOUBLE;
    }

    private VeloraType commonNumericType(VeloraType left, VeloraType right) {
        if (left == VeloraTypes.DOUBLE || right == VeloraTypes.DOUBLE) return VeloraTypes.DOUBLE;
        if (left == VeloraTypes.FLOAT || right == VeloraTypes.FLOAT) return VeloraTypes.FLOAT;
        if (left == VeloraTypes.LONG || right == VeloraTypes.LONG) return VeloraTypes.LONG;
        return VeloraTypes.INT;
    }

    private VeloraType commonType(VeloraType left, VeloraType right) {
        if (left == right) return left;
        if (isNumeric(left) && isNumeric(right)) return commonNumericType(left, right);
        if (isAssignable(left, right)) return right;
        if (isAssignable(right, left)) return left;
        return null;
    }

    private VeloraType mergeLiteralType(VeloraType accumulated, VeloraType current, AstNode node, String label) {
        if (accumulated == null || accumulated == VeloraTypes.UNIT) return current;
        if (current == null || current == VeloraTypes.UNIT) return accumulated;
        VeloraType common = commonType(accumulated, current);
        if (common == null) {
            error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, label + " must have compatible types, got " + accumulated.name() + " and " + current.name(), node.line(), node.column());
            return accumulated;
        }
        return common;
    }

    private boolean areComparable(VeloraType left, VeloraType right) {
        return isNullType(left) || isNullType(right) || left == VeloraTypes.UNIT || right == VeloraTypes.UNIT || left == right || isNumeric(left) && isNumeric(right) || isAssignable(left, right) || isAssignable(right, left);
    }

    private boolean isNullType(VeloraType type) {
        return type != null && type.isNullable() && type.name().equals("Unit?");
    }

    private List<ExpressionNode> bindArguments(String functionName, List<ExpressionNode> args, List<String> parameterNames, List<Boolean> defaults, Scope scope, ResolvedScript.ResolvedFunction currentFn, int line, int column) {
        List<ExpressionNode> bound = new ArrayList<>(Collections.nCopies(parameterNames.size(), null));
        boolean namedStarted = false;
        int positional = 0;
        for (ExpressionNode argument : args) {
            if (argument instanceof NamedArgumentExpressionNode named) {
                namedStarted = true;
                int index = parameterNames.indexOf(named.argumentName());
                if (index < 0) {
                    error(DiagnosticCode.SEMANTIC_NAMED_ARG_UNKNOWN, "Unknown named argument '" + named.argumentName() + "' for " + functionName, named.line(), named.column());
                    checkExpression(named.value(), scope, currentFn);
                } else if (bound.get(index) != null) {
                    error(DiagnosticCode.SEMANTIC_NAMED_ARG_DUPLICATE, "Duplicate argument '" + named.argumentName() + "'", named.line(), named.column());
                    checkExpression(named.value(), scope, currentFn);
                } else {
                    bound.set(index, named.value());
                }
                continue;
            }
            if (namedStarted) {
                error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "Positional arguments cannot follow named arguments in " + functionName, argument.line(), argument.column());
            }
            while (positional < bound.size() && bound.get(positional) != null) positional++;
            if (positional >= bound.size()) {
                error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "Too many arguments for " + functionName, line, column);
                checkExpression(argument, scope, currentFn);
            } else {
                bound.set(positional++, argument);
            }
        }
        for (int i = 0; i < bound.size(); i++) {
            if (bound.get(i) == null && !defaults.get(i)) {
                error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "Missing required argument '" + parameterNames.get(i) + "' for " + functionName, line, column);
            }
        }
        return bound;
    }

    private boolean isApiNamespace(String name) {
        return resolveApiNamespace(name) != null;
    }

    private String resolveApiNamespace(String name) {
        if (apiRegistry.namespaces().contains(name)) return name;
        return importNamespaces.get(name);
    }

    private Map<String, String> resolveImports(ScriptNode script) {
        if (script.imports().isEmpty()) return Map.of();
        Map<String, String> resolved = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (ImportNode imported : script.imports()) {
            if (!names.add(imported.importName())) {
                error(DiagnosticCode.SEMANTIC_DUPLICATE_IMPORT, "Duplicate import: " + imported.importName(), imported.line(), imported.column());
                continue;
            }
            if (apiRegistry.namespaces().contains(imported.alias()) || resolved.containsKey(imported.alias())) {
                error(DiagnosticCode.SEMANTIC_DUPLICATE_IMPORT, "Import alias conflicts with an existing namespace: " + imported.alias(), imported.line(), imported.column());
                continue;
            }
            var descriptor = javaImportRegistry != null ? javaImportRegistry.find(imported.importName()) : null;
            if (descriptor == null) {
                error(DiagnosticCode.SEMANTIC_UNKNOWN_IMPORT, "Unknown Java import: " + imported.importName(), imported.line(), imported.column());
                continue;
            }
            resolved.put(imported.alias(), descriptor.namespace());
        }
        return Map.copyOf(resolved);
    }

    private VeloraType resolveType(TypeNode typeNode, AstNode node) {
        if (typeNode == null) return null;
        String name = typeNode.typeName();
        List<TypeNode> arguments = typeNode.typeArguments();
        VeloraType type;
        if (name.equals("List") || name.equals("Set") || name.equals("Task")) {
            if (arguments.size() != 1) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, name + " requires exactly one type argument", node.line(), node.column());
                return null;
            }
            VeloraType argument = resolveType(arguments.get(0), node);
            if (argument == null) return null;
            if (name.equals("Set") && !argument.isHashable()) error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Set element type must be hashable, got " + argument.name(), node.line(), node.column());
            type = name.equals("List") ? VeloraTypes.list(argument) : name.equals("Set") ? VeloraTypes.set(argument) : VeloraTypes.task(argument);
        } else if (name.equals("Map")) {
            if (arguments.size() != 2) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Map requires exactly two type arguments", node.line(), node.column());
                return null;
            }
            VeloraType key = resolveType(arguments.get(0), node);
            VeloraType value = resolveType(arguments.get(1), node);
            if (key == null || value == null) return null;
            if (!key.isHashable()) error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Map key type must be hashable, got " + key.name(), node.line(), node.column());
            type = VeloraTypes.map(key, value);
        } else {
            if (!arguments.isEmpty()) {
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, name + " does not accept type arguments", node.line(), node.column());
                return null;
            }
            type = resolveBaseType(name, node);
            if (type == null) return null;
        }
        return typeNode.nullable() ? type.nullable() : type;
    }

    private boolean isPersistableType(VeloraType type) {
        VeloraType value = type.nonNull();
        return value == VeloraTypes.BYTE || value == VeloraTypes.INT || value == VeloraTypes.LONG || value == VeloraTypes.FLOAT
                || value == VeloraTypes.DOUBLE || value == VeloraTypes.BOOLEAN || value == VeloraTypes.CHAR || value == VeloraTypes.STRING
                || value == VeloraTypes.DURATION || value == VeloraTypes.UUID;
    }

    private boolean isPersistableSettingType(VeloraType type) {
        return isPersistableType(type) || type.nonNull() instanceof EnumType;
    }

    private VeloraType resolveBaseType(String name, AstNode node) {
        return switch (name) {
            case "Unit", "void" -> VeloraTypes.UNIT;
            case "Nothing" -> VeloraTypes.NOTHING;
            case "Boolean", "boolean" -> VeloraTypes.BOOLEAN;
            case "Byte", "byte" -> VeloraTypes.BYTE;
            case "Int", "int" -> VeloraTypes.INT;
            case "Long", "long" -> VeloraTypes.LONG;
            case "Float", "float" -> VeloraTypes.FLOAT;
            case "Double", "double" -> VeloraTypes.DOUBLE;
            case "Char", "char" -> VeloraTypes.CHAR;
            case "String" -> VeloraTypes.STRING;
            case "Duration" -> VeloraTypes.DURATION;
            case "Vec2" -> VeloraTypes.VEC2;
            case "Vec3" -> VeloraTypes.VEC3;
            case "Color" -> VeloraTypes.COLOR;
            case "UUID" -> VeloraTypes.UUID;
            default -> {
                VeloraType registered = typeRegistry.find(name);
                if (registered != null) yield registered;
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown type: " + name, node.line(), node.column());
                yield null;
            }
        };
    }

    private VeloraType inferTypeFromInitializer(ExpressionNode init) {
        if (init == null) return null;
        if (init instanceof LiteralExpressionNode lit) return lit.kind() == LiteralExpressionNode.LiteralKind.NULL ? null : literalType(lit);
        if (init instanceof CollectionConstructorExpressionNode collection) return collectionType(collection);
        if (init instanceof ListLiteralExpressionNode list) {
            VeloraType element = null;
            for (ExpressionNode expression : list.elements()) {
                VeloraType value = inferTypeFromInitializer(expression);
                if (value == null) return null;
                element = element == null ? value : commonType(element, value);
                if (element == null) return null;
            }
            return VeloraTypes.list(element != null ? element : VeloraTypes.UNIT);
        }
        if (init instanceof MapLiteralExpressionNode map) {
            VeloraType key = null, value = null;
            for (var entry : map.entries()) {
                VeloraType k = inferTypeFromInitializer(entry.getKey());
                VeloraType v = inferTypeFromInitializer(entry.getValue());
                if (k == null || v == null) return null;
                key = key == null ? k : commonType(key, k);
                value = value == null ? v : commonType(value, v);
                if (key == null || value == null) return null;
            }
            return VeloraTypes.map(key != null ? key : VeloraTypes.UNIT, value != null ? value : VeloraTypes.UNIT);
        }
        if (init instanceof MemberAccessExpressionNode member && member.target() instanceof IdentifierExpressionNode identifier) {
            VeloraType type = typeRegistry.find(identifier.name());
            if (type instanceof EnumType enumType && enumType.hasConstant(member.member())) return enumType;
        }
        if (init instanceof QualifiedExpressionNode qualified) {
            VeloraType type = typeRegistry.find(qualified.qualifier());
            if (type instanceof EnumType enumType && enumType.hasConstant(qualified.member())) return enumType;
        }
        if (init instanceof DurationExpressionNode) return VeloraTypes.DURATION;
        if (init instanceof InterpolationExpressionNode) return VeloraTypes.STRING;
        return null;
    }

    private ConstantEvaluation evaluateConstant(ExpressionNode expression, Map<String, ResolvedScript.ResolvedProperty> properties) {
        if (expression instanceof LiteralExpressionNode literal) return new ConstantEvaluation(true, literal.value());
        if (expression instanceof CollectionConstructorExpressionNode collection) {
            return switch (collection.kind()) {
                case LIST -> new ConstantEvaluation(true, new ArrayList<>());
                case SET -> new ConstantEvaluation(true, new LinkedHashSet<>());
                case MAP -> new ConstantEvaluation(true, new LinkedHashMap<>());
            };
        }
        if (expression instanceof MemberAccessExpressionNode member && member.target() instanceof IdentifierExpressionNode identifier) {
            VeloraType type = typeRegistry.find(identifier.name());
            if (type instanceof EnumType enumType) {
                EnumType.Constant constant = enumType.constant(member.member());
                if (constant != null) return new ConstantEvaluation(true, constant.value());
            }
        }
        if (expression instanceof QualifiedExpressionNode qualified) {
            VeloraType type = typeRegistry.find(qualified.qualifier());
            if (type instanceof EnumType enumType) {
                EnumType.Constant constant = enumType.constant(qualified.member());
                if (constant != null) return new ConstantEvaluation(true, constant.value());
            }
        }
        if (expression instanceof IdentifierExpressionNode identifier) {
            ResolvedScript.ResolvedProperty property = properties.get(identifier.name());
            return property != null && property.isConst() ? new ConstantEvaluation(true, property.constValue()) : ConstantEvaluation.NOT_CONSTANT;
        }
        if (expression instanceof UnaryExpressionNode unary) {
            ConstantEvaluation operand = evaluateConstant(unary.operand(), properties);
            if (!operand.constant()) return operand;
            Object value = operand.value();
            try {
                return switch (unary.operator()) {
                    case "!" -> value instanceof Boolean b ? new ConstantEvaluation(true, !b) : ConstantEvaluation.NOT_CONSTANT;
                    case "-" -> value instanceof Number n ? new ConstantEvaluation(true, negateConstant(n)) : ConstantEvaluation.NOT_CONSTANT;
                    case "+" -> value instanceof Number ? operand : ConstantEvaluation.NOT_CONSTANT;
                    default -> ConstantEvaluation.NOT_CONSTANT;
                };
            } catch (ArithmeticException ignored) { return ConstantEvaluation.NOT_CONSTANT; }
        }
        if (expression instanceof BinaryExpressionNode binary) {
            ConstantEvaluation left = evaluateConstant(binary.left(), properties);
            ConstantEvaluation right = evaluateConstant(binary.right(), properties);
            if (!left.constant() || !right.constant()) return ConstantEvaluation.NOT_CONSTANT;
            try { return new ConstantEvaluation(true, binaryConstant(binary.operator(), left.value(), right.value())); }
            catch (IllegalArgumentException | ArithmeticException ignored) { return ConstantEvaluation.NOT_CONSTANT; }
        }
        if (expression instanceof ElvisExpressionNode elvis) {
            ConstantEvaluation left = evaluateConstant(elvis.left(), properties);
            if (!left.constant()) return ConstantEvaluation.NOT_CONSTANT;
            return left.value() != null ? left : evaluateConstant(elvis.right(), properties);
        }
        if (expression instanceof InterpolationExpressionNode interpolation) {
            StringBuilder value = new StringBuilder();
            for (InterpolationExpressionNode.Segment segment : interpolation.segments()) {
                if (segment instanceof InterpolationExpressionNode.Text text) value.append(text.value());
                else if (segment instanceof InterpolationExpressionNode.Expr expr) {
                    ConstantEvaluation part = evaluateConstant(expr.expression(), properties);
                    if (!part.constant()) return ConstantEvaluation.NOT_CONSTANT;
                    value.append(String.valueOf(part.value()));
                }
            }
            return new ConstantEvaluation(true, value.toString());
        }
        return ConstantEvaluation.NOT_CONSTANT;
    }

    private Object binaryConstant(String operator, Object left, Object right) {
        if (operator.equals("+") && (left instanceof String || right instanceof String)) return String.valueOf(left) + right;
        if (operator.equals("==")) return constantEquals(left, right);
        if (operator.equals("!=")) return !constantEquals(left, right);
        if (operator.equals("&&") && left instanceof Boolean a && right instanceof Boolean b) return a && b;
        if (operator.equals("||") && left instanceof Boolean a && right instanceof Boolean b) return a || b;
        if (left instanceof Number a && right instanceof Number b) {
            int comparison = a instanceof Float || a instanceof Double || b instanceof Float || b instanceof Double
                    ? Double.compare(a.doubleValue(), b.doubleValue())
                    : Long.compare(a.longValue(), b.longValue());
            if (operator.equals("<")) return comparison < 0;
            if (operator.equals("<=")) return comparison <= 0;
            if (operator.equals(">")) return comparison > 0;
            if (operator.equals(">=")) return comparison >= 0;
            return numericConstant(operator, a, b);
        }
        throw new IllegalArgumentException();
    }

    private Object numericConstant(String operator, Number left, Number right) {
        if ((operator.equals("/") || operator.equals("%")) && right.doubleValue() == 0) throw new ArithmeticException();
        if (left instanceof Double || right instanceof Double) {
            double a = left.doubleValue(), b = right.doubleValue();
            return switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); };
        }
        if (left instanceof Float || right instanceof Float) {
            float a = left.floatValue(), b = right.floatValue();
            return switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); };
        }
        if (left instanceof Long || right instanceof Long) {
            long a = left.longValue(), b = right.longValue();
            return switch (operator) {
                case "+" -> Math.addExact(a, b);
                case "-" -> Math.subtractExact(a, b);
                case "*" -> Math.multiplyExact(a, b);
                case "/" -> { if (a == Long.MIN_VALUE && b == -1) throw new ArithmeticException("long overflow"); yield a / b; }
                case "%" -> a % b;
                default -> throw new IllegalArgumentException();
            };
        }
        int a = left.intValue(), b = right.intValue();
        return switch (operator) {
            case "+" -> Math.addExact(a, b);
            case "-" -> Math.subtractExact(a, b);
            case "*" -> Math.multiplyExact(a, b);
            case "/" -> { if (a == Integer.MIN_VALUE && b == -1) throw new ArithmeticException("int overflow"); yield a / b; }
            case "%" -> a % b;
            default -> throw new IllegalArgumentException();
        };
    }

    private boolean constantEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            if (a instanceof Float || a instanceof Double || b instanceof Float || b instanceof Double) return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
            return a.longValue() == b.longValue();
        }
        return Objects.equals(left, right);
    }

    private Object negateConstant(Number value) {
        if (value instanceof Double d) return -d;
        if (value instanceof Float f) return -f;
        if (value instanceof Long l) return Math.negateExact(l);
        return Math.negateExact(value.intValue());
    }

    private VeloraType constantType(Object value) {
        if (value == null) return null;
        if (value instanceof Byte) return VeloraTypes.BYTE;
        if (value instanceof Integer || value instanceof Short) return VeloraTypes.INT;
        if (value instanceof Long) return VeloraTypes.LONG;
        if (value instanceof Float) return VeloraTypes.FLOAT;
        if (value instanceof Double) return VeloraTypes.DOUBLE;
        if (value instanceof Boolean) return VeloraTypes.BOOLEAN;
        if (value instanceof Character) return VeloraTypes.CHAR;
        if (value instanceof String) return VeloraTypes.STRING;
        return null;
    }

    private record ConstantEvaluation(boolean constant, Object value) {
        private static final ConstantEvaluation NOT_CONSTANT = new ConstantEvaluation(false, null);
    }

    private void validateScriptAnnotations(ScriptNode script) {
        Set<String> seen = new HashSet<>();
        Set<String> supported = Set.of("Script", "Version", "Author", "Description");
        for (AnnotationNode annotation : script.annotations()) {
            if (!supported.contains(annotation.name())) {
                error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Annotation @" + annotation.name() + " is not supported at script level", annotation.line(), annotation.column());
                continue;
            }
            if (!seen.add(annotation.name())) {
                error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Duplicate @" + annotation.name() + " annotation", annotation.line(), annotation.column());
            }
            if (!annotation.namedArgs().isEmpty()) {
                error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@" + annotation.name() + " accepts one positional String argument", annotation.line(), annotation.column());
            }
            if (annotation.positionalArgs().size() != 1 || !(annotation.positionalArg(0) instanceof String value) || value.isBlank()) {
                error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@" + annotation.name() + " expects one non-blank String argument", annotation.line(), annotation.column());
            }
        }
        if (!seen.contains("Script")) {
            error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Script must declare @Script(\"Name\")", script.line(), script.column());
        }
    }

    private ResolvedScript.ScriptMetadata extractMetadata(ScriptNode script) {
        String id = script.scriptName();
        String name = script.scriptName();
        String version = "";
        String author = "";
        String description = "";
        for (AnnotationNode annotation : script.annotations()) {
            Object value = annotation.positionalArg(0);
            if (!(value instanceof String text)) continue;
            switch (annotation.name()) {
                case "Script" -> name = text;
                case "Version" -> version = text;
                case "Author" -> author = text;
                case "Description" -> description = text;
                default -> { }
            }
        }
        return new ResolvedScript.ScriptMetadata(id, name, version, author, description);
    }

    private List<SettingDescriptor> buildSettings(SettingBlockNode block) {
        List<SettingDescriptor> result = new ArrayList<>();
        if (block == null) return result;
        Set<String> seen = new HashSet<>();
        Map<String, SettingDeclarationNode> declarations = new LinkedHashMap<>();
        int index = 0;
        for (SettingDeclarationNode decl : block.declarations()) {
            if (!seen.add(decl.identifier())) {
                error(DiagnosticCode.SETTING_DUPLICATE_ID, "Duplicate setting identifier '" + decl.identifier() + "'", decl.line(), decl.column());
                continue;
            }
            SettingDescriptor descriptor = buildSettingDescriptor(decl, index++);
            if (descriptor != null) {
                result.add(descriptor);
                declarations.put(descriptor.id(), decl);
            }
        }
        Set<String> names = new HashSet<>();
        for (SettingDescriptor descriptor : result) names.add(descriptor.id());
        Set<String> aliases = new HashSet<>();
        for (SettingDescriptor descriptor : result) descriptor.idAlias().ifPresent(alias -> {
            if (alias.equals(descriptor.id()) || names.contains(alias) || !aliases.add(alias)) {
                SettingDeclarationNode decl = declarations.get(descriptor.id());
                error(DiagnosticCode.SETTING_DUPLICATE_ID, "Conflicting setting idAlias '" + alias + "'", decl.line(), decl.column());
            }
        });
        return result;
    }

    private SettingDescriptor buildSettingDescriptor(SettingDeclarationNode decl, int index) {
        List<Object> positional = decl.positionalArguments();
        Map<String, Object> named = decl.namedArguments();
        String kindName = named.get("kind") instanceof String value ? value : null;
        SettingKind selectedKind = kindName != null ? settingRegistry.find(kindName) : null;
        Set<String> allowed = new HashSet<>(Set.of("min", "max", "step", "minLength", "maxLength", "values", "pattern", "editor", "kind", "description", "order", "advanced", "restartRequired", "secret", "idAlias", "category"));
        if (selectedKind != null) {
            for (SettingKind.Parameter parameter : selectedKind.parameterSchema()) {
                if (parameter.role() != SettingKind.Parameter.ParameterRole.IDENTIFIER
                        && parameter.role() != SettingKind.Parameter.ParameterRole.DISPLAY_NAME
                        && parameter.role() != SettingKind.Parameter.ParameterRole.DEFAULT_VALUE) {
                    allowed.add(parameter.name());
                }
            }
        }
        if (positional.size() > 1) {
            error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "@Setting expects at most one positional display-name argument", decl.line(), decl.column());
        }
        if (!positional.isEmpty() && !(positional.get(0) instanceof String)) {
            error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "@Setting display name must be String", decl.line(), decl.column());
        }
        for (String key : named.keySet()) {
            if (!allowed.contains(key)) error(DiagnosticCode.SEMANTIC_NAMED_ARG_UNKNOWN, "Unknown @Setting argument '" + key + "'", decl.line(), decl.column());
        }
        checkNamedSettingType(named, "description", VeloraTypes.STRING, decl);
        checkNamedSettingType(named, "category", VeloraTypes.STRING, decl);
        checkNamedSettingType(named, "idAlias", VeloraTypes.STRING, decl);
        checkNamedSettingType(named, "pattern", VeloraTypes.STRING, decl);
        checkNamedSettingType(named, "editor", VeloraTypes.STRING, decl);
        checkNamedSettingType(named, "kind", VeloraTypes.STRING, decl);
        checkNamedSettingType(named, "order", VeloraTypes.INT, decl);
        checkNamedSettingType(named, "advanced", VeloraTypes.BOOLEAN, decl);
        checkNamedSettingType(named, "restartRequired", VeloraTypes.BOOLEAN, decl);
        checkNamedSettingType(named, "secret", VeloraTypes.BOOLEAN, decl);
        checkNamedSettingType(named, "minLength", VeloraTypes.INT, decl);
        checkNamedSettingType(named, "maxLength", VeloraTypes.INT, decl);

        ConstantEvaluation evaluation = evaluateConstant(decl.initializer(), Map.of());
        if (!evaluation.constant()) {
            error(DiagnosticCode.SETTING_INVALID_DEFAULT, "Setting '" + decl.identifier() + "' default must be compile-time evaluable", decl.line(), decl.column());
            return null;
        }
        Object defaultValue = evaluation.value();
        VeloraType inferred = inferTypeFromInitializer(decl.initializer());
        if (inferred == null) inferred = constantType(defaultValue);
        VeloraType kindType = null;
        if (kindName != null) {
            if (selectedKind == null) {
                error(DiagnosticCode.SETTING_UNKNOWN_ANNOTATION, "Unknown setting kind '" + kindName + "'", decl.line(), decl.column());
            } else {
                List<Object> kindArguments = validateSettingKind(selectedKind, decl, defaultValue);
                try {
                    kindType = selectedKind.resolveType(new SettingKind.SettingDeclaration(kindName, decl.identifier(), kindArguments, named));
                    if (kindType == null || kindType == VeloraTypes.UNIT || kindType == VeloraTypes.NOTHING) {
                        error(DiagnosticCode.SEMANTIC_UNTYPED_DECLARATION, "Setting kind '" + kindName + "' resolved to an invalid type", decl.line(), decl.column());
                        kindType = null;
                    }
                } catch (RuntimeException ex) {
                    error(DiagnosticCode.SETTING_INVALID_DEFAULT, "Cannot resolve setting kind '" + kindName + "': " + ex.getMessage(), decl.line(), decl.column());
                }
            }
        }

        VeloraType type = decl.declaredType() != null ? resolveType(decl.declaredType(), decl) : kindType != null ? kindType : inferred;
        if (type == null || type == VeloraTypes.UNIT) {
            error(DiagnosticCode.SEMANTIC_UNTYPED_DECLARATION, "Cannot infer type of setting '" + decl.identifier() + "'", decl.line(), decl.column());
            return null;
        }
        if (kindType != null && decl.declaredType() != null && !isAssignable(type, kindType) && !isAssignable(kindType, type)) {
            error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Setting kind '" + kindName + "' expects " + kindType.name() + ", got " + type.name(), decl.line(), decl.column());
        }
        if (!isPersistableSettingType(type)) {
            error(DiagnosticCode.SEMANTIC_INVALID_PERSISTENT_TYPE, "Setting '" + decl.identifier() + "' uses unsupported persistent type " + type.name(), decl.line(), decl.column());
        }
        VeloraType defaultType = constantType(defaultValue);
        if (defaultValue == null && !type.isNullable()) {
            error(DiagnosticCode.SETTING_INVALID_DEFAULT, "Default value for setting '" + decl.identifier() + "' cannot be null", decl.line(), decl.column());
        } else if (defaultType != null && !isAssignableExpression(decl.initializer(), defaultType, type)) {
            error(DiagnosticCode.SETTING_INVALID_DEFAULT, "Default value for setting '" + decl.identifier() + "' expects " + type.name() + ", got " + defaultType.name(), decl.line(), decl.column());
        }

        String displayName = !positional.isEmpty() ? String.valueOf(positional.get(0)) : decl.identifier();
        SettingEditorDescriptor editor = selectedKind != null ? selectedKind.editor().orElse(null) : defaultSettingEditor(type);
        if (named.containsKey("editor") && named.get("editor") != null) editor = SettingEditorDescriptor.of(String.valueOf(named.get("editor")));
        List<SettingDescriptor.Constraint> constraints = new ArrayList<>();
        Object min = named.get("min"), max = named.get("max"), step = named.get("step");
        if (min != null && !(min instanceof Number)) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "@Setting min must be numeric", decl.line(), decl.column());
        if (max != null && !(max instanceof Number)) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "@Setting max must be numeric", decl.line(), decl.column());
        if (step != null && !(step instanceof Number)) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "@Setting step must be numeric", decl.line(), decl.column());
        if (step instanceof Number value && value.doubleValue() <= 0) error(DiagnosticCode.SETTING_OUT_OF_RANGE, "@Setting step must be greater than zero", decl.line(), decl.column());
        Number minLength = named.get("minLength") instanceof Number value ? value : null;
        Number maxLength = named.get("maxLength") instanceof Number value ? value : null;
        if (minLength != null && minLength.intValue() < 0) error(DiagnosticCode.SETTING_OUT_OF_RANGE, "@Setting minLength cannot be negative", decl.line(), decl.column());
        if (maxLength != null && maxLength.intValue() < 0) error(DiagnosticCode.SETTING_OUT_OF_RANGE, "@Setting maxLength cannot be negative", decl.line(), decl.column());
        if (minLength != null && maxLength != null && minLength.intValue() > maxLength.intValue()) error(DiagnosticCode.SETTING_OUT_OF_RANGE, "@Setting minLength cannot exceed maxLength", decl.line(), decl.column());
        if (min instanceof Number a && max instanceof Number b) {
            if (a.doubleValue() > b.doubleValue()) error(DiagnosticCode.SETTING_OUT_OF_RANGE, "Invalid range: " + a + ".." + b, decl.line(), decl.column());
            constraints.add(SettingDescriptor.Constraint.range(min, max));
        } else if (min instanceof Number) constraints.add(SettingDescriptor.Constraint.min(min));
        else if (max instanceof Number) constraints.add(SettingDescriptor.Constraint.max(max));
        if (step instanceof Number) constraints.add(SettingDescriptor.Constraint.step(step));
        if (named.get("minLength") instanceof Number n) constraints.add(SettingDescriptor.Constraint.minLength(n.intValue()));
        if (named.get("maxLength") instanceof Number n) constraints.add(SettingDescriptor.Constraint.maxLength(n.intValue()));
        if (named.get("values") instanceof Collection<?> values) constraints.add(SettingDescriptor.Constraint.values(values));
        else if (named.containsKey("values")) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "@Setting values must be a collection", decl.line(), decl.column());
        String pattern = stringNamed(named, "pattern");
        if (pattern != null) {
            try { java.util.regex.Pattern.compile(pattern); }
            catch (java.util.regex.PatternSyntaxException ex) { error(DiagnosticCode.SETTING_INVALID_DEFAULT, "Invalid setting pattern: " + ex.getDescription(), decl.line(), decl.column()); }
            constraints.add(SettingDescriptor.Constraint.pattern(pattern));
        }

        SettingDescriptor descriptor = new SettingDescriptor(decl.identifier(), displayName, type, defaultValue, editor,
                stringNamed(named, "description"), stringNamed(named, "category"), intNamed(named, "order", 0),
                boolNamed(named, "advanced", false), boolNamed(named, "restartRequired", false), boolNamed(named, "secret", false),
                stringNamed(named, "idAlias"), constraints, index);
        if (defaultValue != null || type.isNullable()) {
            SettingValidationResult validation = SettingValidator.validate(descriptor, SettingValue.of(type, defaultValue));
            if (!validation.isValid()) error(DiagnosticCode.SETTING_INVALID_DEFAULT, validation.errorMessage(), decl.line(), decl.column());
        }
        return descriptor;
    }

    private List<Object> validateSettingKind(SettingKind kind, SettingDeclarationNode decl, Object defaultValue) {
        List<Object> arguments = new ArrayList<>();
        for (SettingKind.Parameter parameter : kind.parameterSchema()) {
            boolean provided;
            Object value;
            switch (parameter.role()) {
                case IDENTIFIER -> {
                    value = decl.identifier();
                    provided = true;
                    continue;
                }
                case DISPLAY_NAME -> {
                    provided = !decl.positionalArguments().isEmpty();
                    value = provided ? decl.positionalArguments().get(0) : null;
                }
                case DEFAULT_VALUE -> {
                    provided = true;
                    value = defaultValue;
                }
                default -> {
                    provided = decl.namedArguments().containsKey(parameter.name());
                    value = decl.namedArguments().get(parameter.name());
                }
            }
            arguments.add(value);
            if (!provided) {
                if (parameter.required()) error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT,
                        "Setting kind '" + kind.name() + "' requires '" + parameter.name() + "'", decl.line(), decl.column());
                continue;
            }
            if (parameter.type() == null) continue;
            if (value == null) {
                if (!parameter.type().isNullable()) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE,
                        "Setting kind parameter '" + parameter.name() + "' cannot be null", decl.line(), decl.column());
                continue;
            }
            VeloraType actual = annotationValueType(value);
            if (actual == null || !isAssignable(actual, parameter.type())) {
                error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE,
                        "Setting kind parameter '" + parameter.name() + "' expects " + parameter.type().name()
                                + ", got " + (actual != null ? actual.name() : value.getClass().getSimpleName()), decl.line(), decl.column());
            }
        }
        return Collections.unmodifiableList(arguments);
    }

    private SettingEditorDescriptor defaultSettingEditor(VeloraType type) {
        VeloraType value = type.nonNull();
        if (value == VeloraTypes.BOOLEAN) return SettingEditorDescriptor.of("boolean");
        if (value == VeloraTypes.STRING) return SettingEditorDescriptor.of("string");
        if (value == VeloraTypes.BYTE || value == VeloraTypes.INT || value == VeloraTypes.LONG || value == VeloraTypes.FLOAT || value == VeloraTypes.DOUBLE) return SettingEditorDescriptor.of("number");
        return null;
    }

    private void checkNamedSettingType(Map<String, Object> named, String name, VeloraType expected, SettingDeclarationNode decl) {
        if (!named.containsKey(name)) return;
        Object value = named.get(name);
        VeloraType actual = annotationValueType(value);
        if (value == null || actual == null || !isAssignable(actual, expected)) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Named setting argument '" + name + "' expects " + expected.name(), decl.line(), decl.column());
    }

    private VeloraType annotationValueType(Object value) {
        VeloraType scalar = constantType(value);
        if (scalar != null || value == null) return scalar;
        if (value instanceof List<?> list) {
            VeloraType element = null;
            for (Object item : list) {
                VeloraType itemType = annotationValueType(item);
                if (itemType == null) continue;
                element = element == null ? itemType : commonType(element, itemType);
                if (element == null) return null;
            }
            return VeloraTypes.list(element != null ? element : VeloraTypes.UNIT);
        }
        return null;
    }

    private String stringArg(AnnotationNode ann, String key) {
        return stringArg(ann, key, null);
    }

    private String stringArg(AnnotationNode ann, String key, String def) {
        Object v = ann.namedArg(key);
        return v != null ? String.valueOf(v) : def;
    }

    private String stringNamed(Map<String, Object> named, String key) {
        Object v = named.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private int intNamed(Map<String, Object> named, String key, int def) {
        Object v = named.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    private boolean boolNamed(Map<String, Object> named, String key, boolean def) {
        Object v = named.get(key);
        if (v instanceof Boolean b) return b;
        return def;
    }

    private void error(DiagnosticCode code, String message, int line, int column) {
        diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, code, message, SourceRange.of("main.vls", line, column)));
    }
}
