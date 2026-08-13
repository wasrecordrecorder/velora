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

                // Extract const value for field initializers (all fields, not just const/static)
                Object constValue = null;
                if (prop.initializer() == null) {
                    if (prop.isConst()) {
                        error(DiagnosticCode.SEMANTIC_MISSING_INITIALIZER, "Constant '" + prop.name() + "' requires an initializer", prop.line(), prop.column());
                    }
                } else {
                    // Check static+const conflict
                    if (prop.isStatic() && prop.isConst()) {
                        error(DiagnosticCode.SEMANTIC_STATIC_CONST_CONFLICT, "Field '" + prop.name() + "' cannot be both static and const", prop.line(), prop.column());
                    }
                    // Check const initializer is compile-time constant
                    if (prop.isConst() && !(prop.initializer() instanceof LiteralExpressionNode)) {
                        error(DiagnosticCode.SEMANTIC_CONST_RUNTIME_INIT, "Constant '" + prop.name() + "' initializer must be a compile-time constant", prop.line(), prop.column());
                    }
                    constValue = extractConstValue(prop.initializer());
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
            VeloraType elemType = VeloraTypes.UNIT;
            if (iterType != null) elemType = iterType;
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
        if (from == to) return true;
        if (to == VeloraTypes.UNIT) return true;
        // Numeric widening
        if (from == VeloraTypes.INT && (to == VeloraTypes.LONG || to == VeloraTypes.DOUBLE || to == VeloraTypes.FLOAT)) return true;
        if (from == VeloraTypes.LONG && to == VeloraTypes.DOUBLE) return true;
        if (from == VeloraTypes.FLOAT && to == VeloraTypes.DOUBLE) return true;
        // Nullable to non-null same base
        if (to != null && from.name().equals(to.name())) return true;
        return false;
    }

    private VeloraType checkExpression(ExpressionNode expr, Scope scope, ResolvedScript.ResolvedFunction currentFn, Set<ScriptPermission> requiredPerms) {
        if (expr == null) return VeloraTypes.UNIT;
        if (expr instanceof LiteralExpressionNode lit) {
            return literalType(lit);
        }
        if (expr instanceof IdentifierExpressionNode id) {
            Symbol s = scope.resolve(id.name());
            if (s == null) {
                // Could be an API namespace (e.g. world, bot, player)
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
            return binaryType(bin.operator(), lt, rt);
        }
        if (expr instanceof UnaryExpressionNode un) {
            checkExpression(un.operand(), scope, currentFn, requiredPerms);
            if (un.operator().equals("!")) return VeloraTypes.BOOLEAN;
            return VeloraTypes.UNIT;
        }
        if (expr instanceof CallExpressionNode call) {
            return checkCall(call, scope, currentFn, requiredPerms);
        }
        if (expr instanceof MemberAccessExpressionNode mem) {
            VeloraType recvType = checkExpression(mem.target(), scope, currentFn, requiredPerms);
            // Resolve member type from struct type or API property
            if (recvType instanceof io.velora.api.type.StructType st && st.hasProperty(mem.member())) {
                return st.property(mem.member()).type();
            }
            // API property access: namespace.property (without call)
            if (mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
                FunctionDescriptor fd = apiRegistry.find(ns.name(), mem.member());
                if (fd != null && fd.permission() != null) {
                    requiredPerms.add(fd.permission());
                    return fd.returnType();
                }
            }
            // Could be a constant namespace access (Blocks.X) — handled in qualified
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
            VeloraType elem = VeloraTypes.UNIT;
            for (ExpressionNode e : list.elements()) {
                elem = checkExpression(e, scope, currentFn, requiredPerms);
            }
            return VeloraTypes.list(elem);
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
            // Check index type
            if (recvType != null && recvType.name().startsWith("List")) {
                if (indexType != null && indexType != VeloraTypes.INT && indexType != VeloraTypes.UNIT) {
                    error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "List index must be int, got " + indexType.name(), idx.line(), idx.column());
                }
            } else if (recvType != null && recvType.name().startsWith("Map<")) {
                // Extract key type from name: "Map<K, V>"
                String name = recvType.name();
                int start = name.indexOf('<');
                int comma = name.indexOf(',');
                if (start >= 0 && comma > start) {
                    String keyTypeName = name.substring(start + 1, comma).trim();
                    if (indexType != null && indexType != VeloraTypes.UNIT) {
                        String indexTypeName = indexType.name();
                        // Handle nullable types
                        if (indexTypeName.endsWith("?")) indexTypeName = indexTypeName.substring(0, indexTypeName.length() - 1);
                        if (!keyTypeName.equals(indexTypeName) && !keyTypeName.equals(indexType.name())) {
                            error(DiagnosticCode.SEMANTIC_INDEX_TYPE_MISMATCH, "Map key type mismatch: expected " + keyTypeName + ", got " + indexType.name(), idx.line(), idx.column());
                        }
                    }
                }
            }
            return VeloraTypes.UNIT;
        }
        if (expr instanceof AssignmentExpressionNode assign) {
            if (assign.target() instanceof IdentifierExpressionNode id) {
                Symbol s = scope.resolve(id.name());
                if (s != null && s.isConstProperty()) {
                    error(DiagnosticCode.SEMANTIC_CONST_ASSIGNMENT, "Cannot assign to constant '" + id.name() + "'", id.line(), id.column());
                }
                if (s != null && s.isSetting()) {
                    error(DiagnosticCode.SEMANTIC_SETTING_WRITE, "Cannot assign to setting '" + id.name() + "' (settings are read-only)", id.line(), id.column());
                }
            }
            checkExpression(assign.target(), scope, currentFn, requiredPerms);
            if (assign.value() != null) checkExpression(assign.value(), scope, currentFn, requiredPerms);
            return VeloraTypes.UNIT;
        }
        if (expr instanceof SpawnExpressionNode spawn) {
            checkCallExpression(spawn.callee(), spawn.arguments(), scope, currentFn, requiredPerms);
            return VeloraTypes.UNIT;
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
                    // Check arity (skip for placeholder functions with 0 params)
                    int required = fd.parameters().size();
                    if (required > 0) {
                        int minArgs = required;
                        for (var p : fd.parameters()) {
                            if (p.hasDefault()) minArgs--;
                        }
                        int actualArgs = args.size();
                        if (actualArgs < minArgs || actualArgs > required) {
                            error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "Wrong number of arguments for " + ns.name() + "." + mem.member() + ": expected " + minArgs + ".." + required + ", got " + actualArgs, mem.line(), mem.column());
                        }
                        // Check argument types
                        for (int i = 0; i < Math.min(actualArgs, required); i++) {
                            VeloraType argType = checkExpression(args.get(i), scope, currentFn, requiredPerms);
                            if (argType != null && argType != VeloraTypes.UNIT) {
                                VeloraType paramType = fd.parameters().get(i).type();
                                if (!isAssignable(argType, paramType)) {
                                    error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Argument " + (i+1) + " type mismatch: expected " + paramType.name() + ", got " + argType.name(), args.get(i).line(), args.get(i).column());
                                }
                            }
                        }
                    } else {
                        for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                    }
                    if (fd.permission() != null) requiredPerms.add(fd.permission());
                    return fd.returnType();
                }
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unknown API function: " + ns.name() + "." + mem.member(), mem.line(), mem.column());
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                return VeloraTypes.UNIT;
            }
            // method call on a value
            checkExpression(mem.target(), scope, currentFn, requiredPerms);
            for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
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
                        // Check named arguments
                        Set<String> namedSeen = new HashSet<>();
                        Set<String> paramNames = new HashSet<>();
                        for (var p : calledFn.parameters()) paramNames.add(p.name());
                        for (ExpressionNode a : args) {
                            if (a instanceof NamedArgumentExpressionNode na) {
                                if (!paramNames.contains(na.argumentName())) {
                                    error(DiagnosticCode.SEMANTIC_NAMED_ARG_UNKNOWN, "Unknown named argument '" + na.argumentName() + "' for function " + id.name(), na.line(), na.column());
                                }
                                if (!namedSeen.add(na.argumentName())) {
                                    error(DiagnosticCode.SEMANTIC_NAMED_ARG_DUPLICATE, "Duplicate named argument '" + na.argumentName() + "'", na.line(), na.column());
                                }
                            }
                        }
                        // Check arity
                        int required = calledFn.parameters().size();
                        int minArgs = required;
                        for (var p : calledFn.parameters()) {
                            if (p.hasDefault()) minArgs--;
                        }
                        int actualArgs = args.size();
                        if (actualArgs < minArgs || actualArgs > required) {
                            error(DiagnosticCode.SEMANTIC_WRONG_ARITY, "Wrong number of arguments for " + id.name() + ": expected " + minArgs + ".." + required + ", got " + actualArgs, id.line(), id.column());
                        }
                        // Check argument types
                        for (int i = 0; i < Math.min(actualArgs, required); i++) {
                            ExpressionNode argExpr = args.get(i);
                            if (argExpr instanceof NamedArgumentExpressionNode na) {
                                argExpr = na.value();
                            }
                            VeloraType argType = checkExpression(argExpr, scope, currentFn, requiredPerms);
                            if (argType != null && argType != VeloraTypes.UNIT) {
                                VeloraType paramType = calledFn.parameters().get(i).type();
                                if (!isAssignable(argType, paramType)) {
                                    error(DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE, "Argument " + (i+1) + " type mismatch: expected " + paramType.name() + ", got " + argType.name(), argExpr.line(), argExpr.column());
                                }
                            }
                        }
                    }
                }
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                return s.type();
            }
            // Check for delay/yield/await in non-async function
            if (id.name().equals("delay") && !currentFn.suspending()) {
                error(DiagnosticCode.SEMANTIC_ASYNC_VIOLATION, "Sync function cannot call delay()", id.line(), id.column());
                for (ExpressionNode a : args) checkExpression(a, scope, currentFn, requiredPerms);
                return VeloraTypes.UNIT;
            }
            // Check for unresolved function calls (not a known built-in)
            if (!id.name().equals("delay") && !id.name().equals("yield") && !id.name().equals("await")
                    && !id.name().equals("spawn") && !id.name().equals("listOf") && !id.name().equals("mapOf")
                    && !id.name().equals("setOf") && !id.name().equals("log")) {
                error(DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL, "Unresolved function: " + id.name(), id.line(), id.column());
            }
            // Could be a stdlib function like delay, listOf, etc.
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

    private VeloraType binaryType(String op, VeloraType lt, VeloraType rt) {
        if (op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=")
                || op.equals(">") || op.equals(">=") || op.equals("&&") || op.equals("||")) {
            return VeloraTypes.BOOLEAN;
        }
        // string concatenation
        if (op.equals("+") && (lt == VeloraTypes.STRING || rt == VeloraTypes.STRING)) {
            return VeloraTypes.STRING;
        }
        // arithmetic: widen
        if (lt == VeloraTypes.DOUBLE || rt == VeloraTypes.DOUBLE) return VeloraTypes.DOUBLE;
        if (lt == VeloraTypes.LONG || rt == VeloraTypes.LONG) return VeloraTypes.LONG;
        if (lt == VeloraTypes.FLOAT || rt == VeloraTypes.FLOAT) return VeloraTypes.FLOAT;
        return VeloraTypes.INT;
    }

    private boolean isApiNamespace(String name) {
        return apiRegistry.namespaces().contains(name);
    }

    private VeloraType resolveType(TypeNode typeNode, AstNode node) {
        if (typeNode == null) return null;
        VeloraType base = resolveBaseType(typeNode.typeName(), node);
        if (base == null) return null;
        // type arguments
        if (!typeNode.typeArguments().isEmpty() && (base == VeloraTypes.UNIT)) {
            // List<T>, Map<K,V>, Set<T>
            VeloraType elem = resolveType(typeNode.typeArguments().get(0), node);
            if (typeNode.typeName().equals("List")) return VeloraTypes.list(elem);
            if (typeNode.typeName().equals("Set")) return VeloraTypes.set(elem);
            if (typeNode.typeName().equals("Map") && typeNode.typeArguments().size() >= 2) {
                VeloraType v = resolveType(typeNode.typeArguments().get(1), node);
                return VeloraTypes.map(elem, v);
            }
        }
        if (typeNode.nullable()) {
            return base.nullable();
        }
        return base;
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
            case "BlockPos" -> VeloraTypes.BLOCK_POS;
            case "Rotation" -> VeloraTypes.ROTATION;
            case "Color" -> VeloraTypes.COLOR;
            case "Key" -> VeloraTypes.KEY;
            case "UUID" -> VeloraTypes.UUID;
            case "Identifier" -> VeloraTypes.IDENTIFIER;
            case "BlockId" -> VeloraTypes.BLOCK_ID;
            case "ItemId" -> VeloraTypes.ITEM_ID;
            case "EntityTypeId" -> VeloraTypes.ENTITY_TYPE_ID;
            case "List", "Map", "Set" -> VeloraTypes.UNIT;
            case "TickEvent", "ChatMessageEvent" -> VeloraTypes.UNIT;
            case "PlayerRef", "BlockRef" -> VeloraTypes.UNIT;
            case "task", "Task" -> VeloraTypes.UNIT;
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

    private Object extractConstValue(ExpressionNode init) {
        if (init == null) return null;
        if (init instanceof LiteralExpressionNode lit) return lit.value();
        if (init instanceof IdentifierExpressionNode id) {
            // Reference to another const field
            ResolvedScript.ResolvedProperty prop = null;
            // Look up in properties already resolved
            // This is a simplification - full const folding would be more complex
            return null;
        }
        return null;
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
