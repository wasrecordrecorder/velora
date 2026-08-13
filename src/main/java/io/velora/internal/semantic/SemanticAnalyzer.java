package io.velora.internal.semantic;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.permission.PermissionSet;
import io.velora.api.permission.ScriptPermission;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.PermissionRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingEditorDescriptor;
import io.velora.api.setting.SettingKind;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.ast.*;

import java.util.*;

/**
 * Semantic analyzer: resolves symbols, infers/checks types, builds setting
 * descriptors, computes required permissions and produces a {@link ResolvedScript}.
 */
public final class SemanticAnalyzer {

    private final TypeRegistry typeRegistry;
    private final SettingRegistry settingRegistry;
    private final ApiRegistry apiRegistry;
    private final ConstantRegistry constantRegistry;
    private final PermissionRegistry permissionRegistry;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private Map<String, ResolvedScript.ResolvedFunction> resolvedFunctions;

    public SemanticAnalyzer(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                            ApiRegistry apiRegistry, ConstantRegistry constantRegistry,
                            PermissionRegistry permissionRegistry) {
        this.typeRegistry = typeRegistry;
        this.settingRegistry = settingRegistry;
        this.apiRegistry = apiRegistry;
        this.constantRegistry = constantRegistry;
        this.permissionRegistry = permissionRegistry;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public ResolvedScript analyze(ScriptNode script) {
        diagnostics.clear();
        // Check for unknown annotations
        for (AnnotationNode ann : script.annotations()) {
            if (!ann.name().equals("Script") && !ann.name().equals("Permissions")
                    && !ann.name().equals("Persistent") && !ann.name().equals("Event")) {
                error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Unknown annotation: @" + ann.name(), ann.line(), ann.column());
            }
        }
        ResolvedScript.ScriptMetadata metadata = extractMetadata(script);
        List<SettingDescriptor> settings = buildSettings(script.settingBlock());
        Map<String, ResolvedScript.ResolvedProperty> properties = new LinkedHashMap<>();
        Map<String, ResolvedScript.ResolvedFunction> functions = new LinkedHashMap<>();
        Map<LifecycleNode.Hook, ResolvedScript.ResolvedFunction> lifecycle = new LinkedHashMap<>();
        List<ResolvedScript.ResolvedEventHandler> eventHandlers = new ArrayList<>();
        Set<ScriptPermission> requiredPerms = new LinkedHashSet<>();
        Set<String> persistentIds = new HashSet<>();

        // Build script-level scope: settings + properties + functions
        Scope scriptScope = new Scope();
        int settingIndex = 0;
        for (SettingDescriptor sd : settings) {
            scriptScope.define(new Symbol(sd.id(), sd.type(), Symbol.Kind.SETTING));
            if (sd.type() == VeloraTypes.BOOLEAN && !sd.id().startsWith("is")) {
                String isName = "is" + Character.toUpperCase(sd.id().charAt(0)) + sd.id().substring(1);
                scriptScope.define(new Symbol(isName, sd.type(), Symbol.Kind.SETTING));
            }
        }

        int fieldIndex = 0;
        int staticIndex = 0;
        int functionIndex = 0;
        for (ScriptMemberNode member : script.members()) {
            if (member instanceof PropertyDeclarationNode prop) {
                // Duplicate field check
                if (properties.containsKey(prop.name())) {
                    error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate field: " + prop.name(), prop.line(), prop.column());
                    continue;
                }
                VeloraType propType = resolveType(prop.declaredType(), prop);
                if (propType == null) {
                    propType = inferTypeFromInitializer(prop.initializer());
                }
                if (propType == null) propType = VeloraTypes.UNIT;
                if (prop.persistent()) {
                    String persistentId = prop.persistentId() != null ? prop.persistentId() : prop.name();
                    if (!isPersistableType(propType)) error(DiagnosticCode.SEMANTIC_INVALID_PERSISTENT_TYPE, "Persistent field '" + prop.name() + "' uses unsupported type " + propType.name(), prop.line(), prop.column());
                    if (persistentId.isBlank()) error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Persistent id cannot be blank", prop.line(), prop.column());
                    else if (!persistentIds.add(persistentId)) error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate persistent id: " + persistentId, prop.line(), prop.column());
                }

                // Extract const value for field initializers (all fields, not just const/static)
                Object constValue = null;
                if (prop.initializer() == null) {
                    if (prop.isConst()) error(DiagnosticCode.SEMANTIC_MISSING_INITIALIZER, "Constant '" + prop.name() + "' requires an initializer", prop.line(), prop.column());
                } else {
                    if (prop.isStatic() && prop.isConst()) error(DiagnosticCode.SEMANTIC_STATIC_CONST_CONFLICT, "Field '" + prop.name() + "' cannot be both static and const", prop.line(), prop.column());
                    ConstantEvaluation evaluation = evaluateConstant(prop.initializer(), properties);
                    if (!evaluation.constant()) {
                        error(prop.isConst() ? DiagnosticCode.SEMANTIC_CONST_RUNTIME_INIT : DiagnosticCode.SEMANTIC_NON_CONSTANT_FIELD_INIT,
                                "Field '" + prop.name() + "' initializer must be compile-time evaluable", prop.line(), prop.column());
                    } else {
                        constValue = evaluation.value();
                        VeloraType valueType = constantType(constValue);
                        if (propType != VeloraTypes.UNIT && valueType != null && !isAssignable(valueType, propType)) {
                            error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "Field '" + prop.name() + "' expects " + propType.name() + ", got " + valueType.name(), prop.line(), prop.column());
                        }
                    }
                }

                int idx = prop.isStatic() ? staticIndex++ : fieldIndex++;
                ResolvedScript.ResolvedProperty rp = new ResolvedScript.ResolvedProperty(
                        prop.name(), propType, !prop.isConst(), prop.persistent(), prop.persistentId(),
                        idx, prop.isStatic(), prop.isConst(), constValue);
                properties.put(prop.name(), rp);
                scriptScope.define(new Symbol(prop.name(), propType, prop.isConst() ? Symbol.Kind.CONST_PROPERTY : Symbol.Kind.PROPERTY));
            } else if (member instanceof FunctionNode fn) {
                // Duplicate method check
                if (functions.containsKey(fn.name())) {
                    error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate method: " + fn.name(), fn.line(), fn.column());
                    continue;
                }
                VeloraType retType = fn.returnType() != null ? resolveType(fn.returnType(), fn) : VeloraTypes.UNIT;
                List<ResolvedScript.ResolvedParam> params = new ArrayList<>();
                Set<String> paramNames = new HashSet<>();
                boolean hasDefault = false;
                for (int i = 0; i < fn.parameters().size(); i++) {
                    ParameterNode p = fn.parameters().get(i);
                    VeloraType pt = resolveType(p.type(), p);
                    if (pt == null) pt = VeloraTypes.UNIT;
                    // Duplicate parameter check
                    if (!paramNames.add(p.name())) {
                        error(DiagnosticCode.SEMANTIC_DUPLICATE_SYMBOL, "Duplicate parameter: " + p.name(), p.line(), p.column());
                    }
                    // Default before required check
                    if (p.hasDefault()) {
                        hasDefault = true;
                    } else if (hasDefault) {
                        error(DiagnosticCode.SEMANTIC_DEFAULT_BEFORE_REQUIRED, "Required parameter '" + p.name() + "' after default parameter", p.line(), p.column());
                    }
                    Object defVal = null;
                    if (p.hasDefault() && p.defaultValue() instanceof LiteralExpressionNode lit) {
                        defVal = lit.value();
                    }
                    params.add(new ResolvedScript.ResolvedParam(p.name(), pt, p.hasDefault(), i, defVal));
                }
                ResolvedScript.ResolvedFunction rf = new ResolvedScript.ResolvedFunction(
                        fn.name(), params, retType, fn.suspending(), fn.body(), functionIndex++, false);
                functions.put(fn.name(), rf);
                scriptScope.define(new Symbol(fn.name(), retType, Symbol.Kind.FUNCTION));
            } else if (member instanceof LifecycleNode lc) {
                VeloraType retType = VeloraTypes.UNIT;
                ResolvedScript.ResolvedFunction rf = new ResolvedScript.ResolvedFunction(
                        lc.hook().name(), List.of(), retType, lc.suspending(), lc.body(), functionIndex++, true);
                lifecycle.put(lc.hook(), rf);
            } else if (member instanceof EventHandlerNode eh) {
                VeloraType paramType = resolveType(eh.parameterType(), eh);
                if (paramType == null) paramType = VeloraTypes.UNIT;
                ResolvedScript.ResolvedEventHandler reh = new ResolvedScript.ResolvedEventHandler(
                        eh.eventReference(), eh.parameterName(), eh.parameterName(), paramType,
                        eh.suspending(), eh.body(), functionIndex++);
                eventHandlers.add(reh);
            }
        }

        // Type-check function bodies and collect required permissions
        this.resolvedFunctions = functions;
        for (ResolvedScript.ResolvedFunction rf : functions.values()) {
            checkFunction(rf, scriptScope, requiredPerms);
        }
        for (ResolvedScript.ResolvedFunction rf : lifecycle.values()) {
            checkFunction(rf, scriptScope, requiredPerms);
        }
        for (ResolvedScript.ResolvedEventHandler eh : eventHandlers) {
            Scope handlerScope = new Scope(scriptScope);
            handlerScope.define(new Symbol(eh.parameterName(), eh.parameterType(), Symbol.Kind.PARAMETER));
            ResolvedScript.ResolvedFunction asFn = new ResolvedScript.ResolvedFunction(
                    eh.functionName(), List.of(new ResolvedScript.ResolvedParam(eh.parameterName(), eh.parameterType(), false, 0, null)),
                    VeloraTypes.BOOLEAN, eh.suspending(), eh.body(), eh.functionIndex(), true);
            checkFunction(asFn, handlerScope, requiredPerms);
        }

        PermissionSet maximum = extractMaximumPermissions(script);
        PermissionSet required = PermissionSet.of(requiredPerms);
        // Validate required ⊆ maximum only when @Permissions is explicitly declared
        if (maximum != null && !maximum.containsAll(required)) {
            for (ScriptPermission p : required.all()) {
                if (!maximum.contains(p)) {
                    error(DiagnosticCode.SEMANTIC_PERMISSION_EXCEEDED,
                            "Script requires permission '" + p.id() + "' which is not in @Permissions.maximum", 1, 1);
                }
            }
        }

        ResolvedScript result = new ResolvedScript(metadata, settings, properties, functions, lifecycle, eventHandlers,
                required, maximum, metadata.languageVersion() != null ? metadata.languageVersion() : 1);
        result.setApiRegistry(apiRegistry);
        return result;
    }

    private void checkFunction(ResolvedScript.ResolvedFunction rf, Scope parentScope, Set<ScriptPermission> requiredPerms) {
        Scope fnScope = new Scope(parentScope);
        for (ResolvedScript.ResolvedParam p : rf.parameters()) {
            fnScope.define(new Symbol(p.name(), p.type(), Symbol.Kind.PARAMETER));
        }
        if (rf.body() != null) {
            checkBlock(rf.body(), fnScope, rf, requiredPerms);
            if (rf.returnType() != VeloraTypes.UNIT && !rf.isLifecycle() && !hasReturnStatement(rf.body())) {
                error(DiagnosticCode.SEMANTIC_MISSING_RETURN, "Missing return in function " + rf.name(), rf.body().line(), rf.body().column());
            }
        }
    }

    private boolean hasReturnStatement(BlockNode block) {
        for (StatementNode stmt : block.statements()) {
            if (stmt instanceof ReturnStatementNode) return true;
            if (stmt instanceof IfStatementNode iff) {
                if (iff.thenBlock() != null && hasReturnStatement(iff.thenBlock())) return true;
                if (iff.elseBlock() != null && hasReturnStatement(iff.elseBlock())) return true;
            }
        }
        return false;
    }

    private boolean isPrimitiveType(VeloraType type) {
        return type == VeloraTypes.INT || type == VeloraTypes.LONG || type == VeloraTypes.DOUBLE
            || type == VeloraTypes.FLOAT || type == VeloraTypes.BOOLEAN || type == VeloraTypes.BYTE
            || type == VeloraTypes.CHAR;
    }

    private void checkBlock(BlockNode block, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms) {
        Scope blockScope = new Scope(scope);
        for (StatementNode stmt : block.statements()) {
            checkStatement(stmt, blockScope, currentFn, requiredPerms);
        }
    }

    private void checkStatement(StatementNode stmt, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms) {
        if (stmt instanceof VariableDeclarationNode vd) {
            VeloraType type = vd.declaredType() != null ? resolveType(vd.declaredType(), vd) : null;
            if (vd.initializer() != null) {
                VeloraType initType = checkExpression(vd.initializer(), scope, currentFn, requiredPerms);
                if (type == null) type = initType;
                else if (initType != null && initType != VeloraTypes.UNIT && !isAssignable(initType, type)) {
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
            VeloraType condType = checkExpression(iff.condition(), scope, currentFn, requiredPerms);
            if (condType != null && condType != VeloraTypes.BOOLEAN && condType != VeloraTypes.UNIT && !condType.name().equals("Boolean?")) {
                error(DiagnosticCode.SEMANTIC_NON_BOOLEAN_CONDITION, "If condition must be boolean, got " + condType.name(), iff.line(), iff.column());
            }
            if (iff.thenBlock() != null) checkBlock(iff.thenBlock(), scope, currentFn, requiredPerms);
            if (iff.elseBlock() != null) checkBlock(iff.elseBlock(), scope, currentFn, requiredPerms);
        } else if (stmt instanceof WhileStatementNode ws) {
            VeloraType condType = checkExpression(ws.condition(), scope, currentFn, requiredPerms);
            if (condType != null && condType != VeloraTypes.BOOLEAN && condType != VeloraTypes.UNIT && !condType.name().equals("Boolean?")) {
                error(DiagnosticCode.SEMANTIC_NON_BOOLEAN_CONDITION, "While condition must be boolean, got " + condType.name(), ws.line(), ws.column());
            }
            if (ws.body() != null) checkBlock(ws.body(), scope, currentFn, requiredPerms);
        } else if (stmt instanceof ForStatementNode fs) {
            VeloraType iterType = checkExpression(fs.iterable(), scope, currentFn, requiredPerms);
            if (iterType != null && !iterType.name().startsWith("List") && iterType != VeloraTypes.UNIT) {
                error(DiagnosticCode.SEMANTIC_NON_ITERABLE, "For loop iterable must be a List, got " + iterType.name(), fs.line(), fs.column());
            }
            Scope forScope = new Scope(scope);
            VeloraType elemType = VeloraTypes.listElement(iterType);
            if (elemType == null) elemType = VeloraTypes.UNIT;
            forScope.define(new Symbol(fs.variable(), elemType, Symbol.Kind.LOCAL));
            if (fs.body() != null) checkBlock(fs.body(), forScope, currentFn, requiredPerms);
        } else if (stmt instanceof WhenStatementNode ws) {
            VeloraType subjType = checkExpression(ws.subject(), scope, currentFn, requiredPerms);
            for (WhenStatementNode.Case c : ws.cases()) {
                for (ExpressionNode cond : c.conditions()) {
                    checkExpression(cond, scope, currentFn, requiredPerms);
                }
                if (c.body() != null) checkBlock(c.body(), scope, currentFn, requiredPerms);
            }
            if (ws.elseBody() != null) checkBlock(ws.elseBody(), scope, currentFn, requiredPerms);
        } else if (stmt instanceof ReturnStatementNode rs) {
            if (rs.value() != null) {
                VeloraType retExprType = checkExpression(rs.value(), scope, currentFn, requiredPerms);
                // Check void method returning a value
                if (currentFn.returnType() == VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_VOID_RETURN_VALUE, "Void function cannot return a value", rs.line(), rs.column());
                }
                // Check return type mismatch (only for non-void, non-unit return types)
                if (currentFn.returnType() != null && currentFn.returnType() != VeloraTypes.UNIT && retExprType != null && retExprType != VeloraTypes.UNIT) {
                    if (!isAssignable(retExprType, currentFn.returnType())) {
                        error(DiagnosticCode.SEMANTIC_WRONG_RETURN_TYPE, "Return type mismatch: expected " + currentFn.returnType().name() + ", got " + retExprType.name(), rs.line(), rs.column());
                    }
                }
            }
        } else if (stmt instanceof ExpressionStatementNode es) {
            checkExpression(es.expression(), scope, currentFn, requiredPerms);
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

    private VeloraType checkExpression(ExpressionNode expr, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms) {
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
            VeloraType lt = checkExpression(bin.left(), scope, currentFn, requiredPerms);
            VeloraType rt = checkExpression(bin.right(), scope, currentFn, requiredPerms);
            return checkBinary(bin, lt, rt);
        }
        if (expr instanceof UnaryExpressionNode un) {
            VeloraType operandType = checkExpression(un.operand(), scope, currentFn, requiredPerms);
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
            return checkCall(call, scope, currentFn, requiredPerms);
        }
        if (expr instanceof MemberAccessExpressionNode mem) {
            VeloraType recvType = checkExpression(mem.target(), scope, currentFn, requiredPerms);
            if (recvType instanceof io.velora.api.type.StructType st && st.hasProperty(mem.member())) return st.property(mem.member()).type();
            if (recvType == VeloraTypes.STRING && mem.member().equals("length")) return VeloraTypes.INT;
            if ((VeloraTypes.listElement(recvType) != null || VeloraTypes.mapKey(recvType) != null || VeloraTypes.setElement(recvType) != null) && mem.member().equals("size")) return VeloraTypes.INT;
            if (mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
                FunctionDescriptor fd = apiRegistry.find(ns.name(), mem.member());
                if (fd != null && fd.parameters().isEmpty()) {
                    if (fd.permission() != null) requiredPerms.add(fd.permission());
                    return fd.returnType();
                }
            }
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
            VeloraType lt = checkExpression(el.left(), scope, currentFn, requiredPerms);
            VeloraType rt = checkExpression(el.right(), scope, currentFn, requiredPerms);
            return rt;
        }
        if (expr instanceof IsExpressionNode is) {
            checkExpression(is.operand(), scope, currentFn, requiredPerms);
            return VeloraTypes.BOOLEAN;
        }
        if (expr instanceof ListLiteralExpressionNode list) {
            VeloraType elem = null;
            for (ExpressionNode e : list.elements()) {
                VeloraType current = checkExpression(e, scope, currentFn, requiredPerms);
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
                VeloraType currentKey = checkExpression(entry.getKey(), scope, currentFn, requiredPerms);
                VeloraType currentValue = checkExpression(entry.getValue(), scope, currentFn, requiredPerms);
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
        if (expr instanceof InterpolationExpressionNode interp) {
            for (InterpolationExpressionNode.Segment seg : interp.segments()) {
                if (seg instanceof InterpolationExpressionNode.Expr e) {
                    checkExpression(e.expression(), scope, currentFn, requiredPerms);
                }
            }
            return VeloraTypes.STRING;
        }
        if (expr instanceof DurationExpressionNode dur) {
            checkExpression(dur.amount(), scope, currentFn, requiredPerms);
            return VeloraTypes.DURATION;
        }
        if (expr instanceof IndexExpressionNode idx) {
            VeloraType recvType = checkExpression(idx.receiver(), scope, currentFn, requiredPerms);
            VeloraType indexType = checkExpression(idx.index(), scope, currentFn, requiredPerms);
            VeloraType elementType = VeloraTypes.listElement(recvType);
            if (elementType != null) {
                if (indexType != null && indexType != VeloraTypes.INT && indexType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "List index must be Int, got " + indexType.name(), idx.line(), idx.column());
                }
                return elementType;
            }
            VeloraType keyType = VeloraTypes.mapKey(recvType);
            if (keyType != null) {
                if (indexType != null && indexType != VeloraTypes.UNIT && !isAssignable(indexType, keyType)) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "Map key type mismatch: expected " + keyType.name() + ", got " + indexType.name(), idx.line(), idx.column());
                }
                VeloraType valueType = VeloraTypes.mapValue(recvType);
                return valueType == null ? VeloraTypes.UNIT : valueType;
            }
            return VeloraTypes.UNIT;
        }
        if (expr instanceof AssignmentExpressionNode assign) {
            VeloraType targetType = checkExpression(assign.target(), scope, currentFn, requiredPerms);
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
            VeloraType valueType = assign.value() == null ? VeloraTypes.UNIT : checkExpression(assign.value(), scope, currentFn, requiredPerms);
            if (assign.operator().equals("=")) {
                if (targetType != VeloraTypes.UNIT && valueType != VeloraTypes.UNIT && !isAssignable(valueType, targetType)) {
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
                checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn, requiredPerms);
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "spawn requires a script function", spawn.line(), spawn.column());
                return VeloraTypes.task(VeloraTypes.UNIT);
            }
            Symbol symbol = scope.resolve(id.name());
            if (symbol == null || !symbol.isFunction()) {
                checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn, requiredPerms);
                error(DiagnosticCode.SEMANTIC_TYPE_MISMATCH, "spawn requires a script function", spawn.line(), spawn.column());
                return VeloraTypes.task(VeloraTypes.UNIT);
            }
            VeloraType resultType = checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn, requiredPerms);
            return VeloraTypes.task(resultType == null ? VeloraTypes.UNIT : resultType);
        }
        return VeloraTypes.UNIT;
    }

    private VeloraType checkCall(CallExpressionNode call, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms) {
        return checkCallExpression(call.callee(), call.arguments(), scope, currentFn, requiredPerms);
    }

    private VeloraType checkCallExpression(ExpressionNode callee, List<ExpressionNode> args, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms) {
        // Resolve callee: could be member access (namespace.function) or identifier (user function)
        if (callee instanceof MemberAccessExpressionNode mem) {
            if (mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
                // API call: namespace.function(args)
                FunctionDescriptor fd = apiRegistry.find(ns.name(), mem.member());
                if (fd != null) {
                    List<ExpressionNode> bound = bindArguments(fd.qualifiedName(), args, fd.parameters().stream().map(io.velora.api.function.ParameterDescriptor::name).toList(), fd.parameters().stream().map(io.velora.api.function.ParameterDescriptor::hasDefault).toList(), scope, currentFn, requiredPerms, mem.line(), mem.column());
                    for (int i = 0; i < bound.size(); i++) {
                        ExpressionNode argExpr = bound.get(i);
                        if (argExpr == null) continue;
                        VeloraType argType = checkExpression(argExpr, scope, currentFn, requiredPerms);
                        VeloraType paramType = fd.parameters().get(i).type();
                        if (argType != null && argType != VeloraTypes.UNIT && !isAssignable(argType, paramType)) {
                            error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Argument '" + fd.parameters().get(i).name() + "' type mismatch: expected " + paramType.name() + ", got " + argType.name(), argExpr.line(), argExpr.column());
                        }
                    }
                    if (fd.suspending() && !currentFn.suspending()) {
                        error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call async API '" + fd.qualifiedName() + "'", mem.line(), mem.column());
                    }
                    if (fd.permission() != null) requiredPerms.add(fd.permission());
                    return fd.returnType();
                }
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown API function: " + ns.name() + "." + mem.member(), mem.line(), mem.column());
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                return VeloraTypes.UNIT;
            }
            checkExpression(mem.target(), scope, currentFn, requiredPerms);
            for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
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
                        List<ExpressionNode> bound = bindArguments(id.name(), args, calledFn.parameters().stream().map(ResolvedScript.ResolvedParam::name).toList(), calledFn.parameters().stream().map(ResolvedScript.ResolvedParam::hasDefault).toList(), scope, currentFn, requiredPerms, id.line(), id.column());
                        for (int i = 0; i < bound.size(); i++) {
                            ExpressionNode argExpr = bound.get(i);
                            if (argExpr == null) continue;
                            VeloraType argType = checkExpression(argExpr, scope, currentFn, requiredPerms);
                            VeloraType paramType = calledFn.parameters().get(i).type();
                            if (argType != null && argType != VeloraTypes.UNIT && !isAssignable(argType, paramType)) {
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
                    for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                    return VeloraTypes.UNIT;
                }
                VeloraType delayType = checkExpression(args.get(0), scope, currentFn, requiredPerms);
                if (delayType != VeloraTypes.DURATION && !isNumeric(delayType) && delayType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "delay expects Duration or a nanosecond number, got " + delayType.name(), args.get(0).line(), args.get(0).column());
                }
                return VeloraTypes.UNIT;
            }
            if (id.name().equals("yield")) {
                if (!currentFn.suspending()) error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call yield()", id.line(), id.column());
                if (!args.isEmpty()) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "yield expects no arguments", id.line(), id.column());
                    for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                }
                return VeloraTypes.UNIT;
            }
            if (id.name().equals("await")) {
                if (!currentFn.suspending()) error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call await()", id.line(), id.column());
                if (args.size() != 1) {
                    error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "await expects exactly one Task", id.line(), id.column());
                    for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                    return VeloraTypes.UNIT;
                }
                VeloraType taskType = checkExpression(args.get(0), scope, currentFn, requiredPerms);
                VeloraType resultType = VeloraTypes.taskResult(taskType == null ? VeloraTypes.UNIT : taskType.nonNull());
                if (resultType == null) {
                    if (taskType != VeloraTypes.UNIT) error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "await expects Task<T>, got " + taskType.name(), args.get(0).line(), args.get(0).column());
                    return VeloraTypes.UNIT;
                }
                return resultType;
            }
            if (id.name().equals("spawn")) {
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Use the spawn keyword before a script function call", id.line(), id.column());
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                return VeloraTypes.UNIT;
            }
            error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unresolved function: " + id.name(), id.line(), id.column());
            for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
            return VeloraTypes.UNIT;
        }
        checkExpression(callee, scope, currentFn, requiredPerms);
        for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
        return VeloraTypes.UNIT;
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

    private List<ExpressionNode> bindArguments(String functionName, List<ExpressionNode> args, List<String> parameterNames, List<Boolean> defaults, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms, int line, int column) {
        List<ExpressionNode> bound = new ArrayList<>(Collections.nCopies(parameterNames.size(), null));
        boolean namedStarted = false;
        int positional = 0;
        for (ExpressionNode argument : args) {
            if (argument instanceof NamedArgumentExpressionNode named) {
                namedStarted = true;
                int index = parameterNames.indexOf(named.argumentName());
                if (index < 0) {
                    error(DiagnosticCode.SEMANTIC_NAMED_ARG_UNKNOWN, "Unknown named argument '" + named.argumentName() + "' for " + functionName, named.line(), named.column());
                    checkExpression(named.value(), scope, currentFn, requiredPerms);
                } else if (bound.get(index) != null) {
                    error(DiagnosticCode.SEMANTIC_NAMED_ARG_DUPLICATE, "Duplicate argument '" + named.argumentName() + "'", named.line(), named.column());
                    checkExpression(named.value(), scope, currentFn, requiredPerms);
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
                checkExpression(argument, scope, currentFn, requiredPerms);
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
        return apiRegistry.namespaces().contains(name);
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
        if (init instanceof LiteralExpressionNode lit) return literalType(lit);
        return null;
    }

    private ConstantEvaluation evaluateConstant(ExpressionNode expression, Map<String, ResolvedScript.ResolvedProperty> properties) {
        if (expression instanceof LiteralExpressionNode literal) return new ConstantEvaluation(true, literal.value());
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
            if (operator.equals("<")) return a.doubleValue() < b.doubleValue();
            if (operator.equals("<=")) return a.doubleValue() <= b.doubleValue();
            if (operator.equals(">")) return a.doubleValue() > b.doubleValue();
            if (operator.equals(">=")) return a.doubleValue() >= b.doubleValue();
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
        long a = left.longValue(), b = right.longValue();
        long result = switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); };
        if (left instanceof Long || right instanceof Long) return result;
        return (int) result;
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
        if (value instanceof Long l) return -l;
        return -value.intValue();
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

    private ResolvedScript.ScriptMetadata extractMetadata(ScriptNode script) {
        String id = null, name = script.scriptName(), version = "1.0.0", author = "", description = "";
        Integer languageVersion = 1;
        String minEngineVersion = null, website = null;
        for (AnnotationNode ann : script.annotations()) {
            if (ann.name().equals("Script")) {
                id = stringArg(ann, "id");
                name = stringArg(ann, "name", name);
                version = stringArg(ann, "version", version);
                author = stringArg(ann, "author", author);
                description = stringArg(ann, "description", description);
                Object lv = ann.namedArg("languageVersion");
                if (lv instanceof Number n) languageVersion = n.intValue();
                minEngineVersion = stringArg(ann, "minEngineVersion");
                website = stringArg(ann, "website");
            }
        }
        if (id == null) {
            id = script.scriptName();
        }
        return new ResolvedScript.ScriptMetadata(id, name, version, author, description, languageVersion, minEngineVersion, website);
    }

    private PermissionSet extractMaximumPermissions(ScriptNode script) {
        boolean declared = false;
        Set<ScriptPermission> perms = new LinkedHashSet<>();
        for (AnnotationNode ann : script.annotations()) {
            if (ann.name().equals("Permissions")) {
                declared = true;
                // V2: @Permissions(Permission.A, Permission.B) - positional args
                for (Object o : ann.positionalArgs()) {
                    ScriptPermission p = resolvePermission(String.valueOf(o));
                    if (p != null) {
                        perms.add(p);
                    } else {
                        error(DiagnosticCode.SEMANTIC_UNKNOWN_PERMISSION, "Unknown permission: " + o, ann.line(), ann.column());
                    }
                }
                // V1 fallback: @Permissions(maximum = [Permission.A, ...])
                Object max = ann.namedArg("maximum");
                if (max instanceof List<?> list) {
                    for (Object o : list) {
                        ScriptPermission p = resolvePermission(String.valueOf(o));
                        if (p != null) {
                            perms.add(p);
                        } else {
                            error(DiagnosticCode.SEMANTIC_UNKNOWN_PERMISSION, "Unknown permission: " + o, ann.line(), ann.column());
                        }
                    }
                }
            }
        }
        // Return null when @Permissions is not declared at all (no maximum constraint)
        return declared ? PermissionSet.of(perms) : null;
    }

    private ScriptPermission resolvePermission(String id) {
        // Permission ids in script look like "Permission.WORLD_READ" or "client.world.read"
        String resolved = id;
        if (id.startsWith("Permission.")) {
            resolved = "client." + id.substring("Permission.".length()).toLowerCase().replace('_', '.');
        }
        ScriptPermission p = permissionRegistry.find(resolved);
        if (p == null) {
            // try the raw id
            p = permissionRegistry.find(id);
        }
        if (p == null && id.startsWith("Permission.")) {
            // try without the "Permission." prefix (e.g. "PLAYER_CONTROL")
            p = permissionRegistry.find(id.substring("Permission.".length()));
        }
        return p;
    }

    private List<SettingDescriptor> buildSettings(SettingBlockNode block) {
        List<SettingDescriptor> result = new ArrayList<>();
        if (block == null) return result;
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (SettingDeclarationNode decl : block.declarations()) {
            SettingKind kind = settingRegistry.find(decl.annotationName());
            if (kind == null) {
                error(DiagnosticCode.SETTING_UNKNOWN_ANNOTATION, "Unknown setting annotation '@" + decl.annotationName() + "'", decl.line(), decl.column());
                continue;
            }
            if (!seen.add(decl.identifier())) {
                error(DiagnosticCode.SETTING_DUPLICATE_ID, "Duplicate setting identifier '" + decl.identifier() + "'", decl.line(), decl.column());
                continue;
            }
            SettingDescriptor sd = buildSettingDescriptor(kind, decl, index++);
            if (sd != null) result.add(sd);
        }
        return result;
    }

    private SettingDescriptor buildSettingDescriptor(SettingKind kind, SettingDeclarationNode decl, int index) {
        List<Object> pos = decl.positionalArguments();
        Map<String, Object> named = decl.namedArguments();
        String id = decl.identifier();
        String displayName = pos.size() > 0 ? String.valueOf(pos.get(0)) : id;
        Object defaultValue = null;
        VeloraType type = kind.resolveType(new SettingKind.SettingDeclaration(decl.annotationName(), decl.identifier(), pos, named));

        String description = stringNamed(named, "description");
        String category = stringNamed(named, "category");
        int order = intNamed(named, "order", 0);
        boolean advanced = boolNamed(named, "advanced", false);
        boolean restartRequired = boolNamed(named, "restartRequired", false);
        boolean secret = boolNamed(named, "secret", false);
        String idAlias = stringNamed(named, "idAlias");

        List<SettingDescriptor.Constraint> constraints = new ArrayList<>();
        SettingEditorDescriptor editor = kind.editor().orElse(null);

        // V2: @Number id ("name", min..max, step, default, @Number.Slider)
        // Parser expands min..max into two positional args
        switch (kind.name()) {
            case "Slider", "Number" -> {
                Object min = pos.size() > 1 ? pos.get(1) : null;
                Object max = pos.size() > 2 ? pos.get(2) : null;
                Object step = pos.size() > 3 ? pos.get(3) : null;
                defaultValue = pos.size() > 4 ? pos.get(4) : null;
                if (min != null && max != null) {
                    constraints.add(SettingDescriptor.Constraint.range(min, max));
                    double mn = ((Number) min).doubleValue();
                    double mx = ((Number) max).doubleValue();
                    if (mn > mx) {
                        error(DiagnosticCode.SETTING_OUT_OF_RANGE, "Invalid range: min " + mn + " > max " + mx, decl.line(), decl.column());
                    }
                    if (defaultValue instanceof Number dn) {
                        double dv = dn.doubleValue();
                        if (dv < mn || dv > mx) {
                            error(DiagnosticCode.SETTING_OUT_OF_RANGE, "Default value " + dv + " is outside range " + mn + ".." + mx, decl.line(), decl.column());
                        }
                    }
                }
                if (step != null) constraints.add(SettingDescriptor.Constraint.step(step));
            }
            case "String", "Text" -> {
                Object minLen = pos.size() > 1 ? pos.get(1) : null;
                Object maxLen = pos.size() > 2 ? pos.get(2) : null;
                defaultValue = pos.size() > 3 ? pos.get(3) : null;
                if (minLen instanceof Number mn && maxLen instanceof Number mx) {
                    if (mn.intValue() > mx.intValue()) {
                        error(DiagnosticCode.SETTING_OUT_OF_RANGE, "Invalid string range: min " + mn + " > max " + mx, decl.line(), decl.column());
                    }
                    constraints.add(SettingDescriptor.Constraint.maxLength(mx.intValue()));
                } else if (maxLen instanceof Number n) {
                    constraints.add(SettingDescriptor.Constraint.maxLength(n.intValue()));
                }
                String pattern = stringNamed(named, "pattern");
                if (pattern != null) constraints.add(SettingDescriptor.Constraint.pattern(pattern));
            }
            case "Boolean" -> {
                defaultValue = pos.size() > 1 ? pos.get(1) : null;
            }
        }

        return new SettingDescriptor(id, displayName, type, defaultValue, editor, description, category,
                order, advanced, restartRequired, secret, idAlias, constraints, index);
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
