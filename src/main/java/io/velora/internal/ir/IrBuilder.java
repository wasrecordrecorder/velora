package io.velora.internal.ir;

import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.permission.PermissionSet;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.ast.*;
import io.velora.internal.semantic.ResolvedScript;

import java.util.*;

public final class IrBuilder {

    private final ResolvedScript resolved;
    private final ApiRegistry apiRegistry;
    private Map<String, Integer> localIndices;
    private final Map<String, Integer> settingIndexMap = new HashMap<>();

    public IrBuilder(ResolvedScript resolved) {
        this(resolved, null);
    }

    public IrBuilder(ResolvedScript resolved, ApiRegistry apiRegistry) {
        this.resolved = resolved;
        this.apiRegistry = apiRegistry;
        buildSettingIndexMap();
    }

    private void buildSettingIndexMap() {
        List<SettingDescriptor> settings = resolved.settings();
        for (int i = 0; i < settings.size(); i++) {
            SettingDescriptor sd = settings.get(i);
            settingIndexMap.put(sd.id(), i);
            if (sd.type() == VeloraTypes.BOOLEAN && !sd.id().startsWith("is")) {
                String isName = "is" + Character.toUpperCase(sd.id().charAt(0)) + sd.id().substring(1);
                settingIndexMap.put(isName, i);
            }
        }
    }

    public IrModule build() {
        List<IrFunction> functions = new ArrayList<>();
        List<String> lifecycleHooks = new ArrayList<>();
        List<IrModule.EventHandlerInfo> eventHandlers = new ArrayList<>();
        List<String> persistentFieldIds = new ArrayList<>();
        List<String> persistentFieldTypes = new ArrayList<>();
        List<Integer> persistentFieldIndices = new ArrayList<>();
        List<Boolean> persistentFieldIsStatic = new ArrayList<>();
        List<IrModule.FieldInitializer> fieldInits = new ArrayList<>();

        for (ResolvedScript.ResolvedProperty prop : resolved.properties().values()) {
            if (prop.persistent()) {
                persistentFieldIds.add(prop.persistentId());
                persistentFieldTypes.add(prop.type().name());
                persistentFieldIndices.add(prop.fieldIndex());
                persistentFieldIsStatic.add(prop.isStatic());
            }
            // Collect initializers for non-const fields (instance and static)
            if (!prop.isConst() && prop.constValue() != null) {
                fieldInits.add(new IrModule.FieldInitializer(
                        prop.fieldIndex(), prop.isStatic(), objectToValue(prop.constValue())));
            }
        }

        for (ResolvedScript.ResolvedFunction rf : resolved.functions().values()) {
            functions.add(buildFunction(rf));
        }
        for (var entry : resolved.lifecycle().entrySet()) {
            functions.add(buildFunction(entry.getValue()));
            lifecycleHooks.add(entry.getKey().name());
        }
        for (ResolvedScript.ResolvedEventHandler eh : resolved.eventHandlers()) {
            functions.add(buildEventHandler(eh));
            eventHandlers.add(new IrModule.EventHandlerInfo(
                    eh.eventReference(), eh.functionName(), eh.functionIndex(), eh.suspending()));
        }

        return new IrModule(
                resolved.metadata().id(),
                resolved.metadata().name(),
                resolved.metadata().version(),
                resolved.languageVersion(),
                functions,
                resolved.settings(),
                persistentFieldIds,
                persistentFieldTypes,
                persistentFieldIndices,
                persistentFieldIsStatic,
                resolved.requiredPermissions(),
                resolved.maximumPermissions(),
                lifecycleHooks,
                eventHandlers,
                fieldInits,
                resolved.metadata().author(),
                resolved.metadata().description()
        );
    }

    private IrFunction buildFunction(ResolvedScript.ResolvedFunction rf) {
        List<IrFunction.IrParam> params = new ArrayList<>();
        localIndices = new HashMap<>();
        for (int i = 0; i < rf.parameters().size(); i++) {
            ResolvedScript.ResolvedParam p = rf.parameters().get(i);
            params.add(new IrFunction.IrParam(p.name(), p.type()));
            localIndices.put(p.name(), i);
        }
        List<IrInstruction> instrs = new ArrayList<>();
        int localCount = rf.parameters().size();
        int maxStack = 0;

        if (rf.body() != null) {
            int[] depth = buildBlock(rf.body(), instrs, localCount);
            localCount = depth[0];
            maxStack = depth[1];
        }
        if (instrs.isEmpty() || !(instrs.get(instrs.size() - 1) instanceof IrInstruction.Return)) {
            instrs.add(new IrInstruction.Return());
        }

        IrBlock block = new IrBlock(0, instrs, List.of(), List.of());
        return new IrFunction(rf.name(), rf.functionIndex(), params, rf.returnType(),
                rf.suspending(), rf.isLifecycle(), List.of(block), localCount, Math.max(maxStack, 1));
    }

    private IrFunction buildEventHandler(ResolvedScript.ResolvedEventHandler eh) {
        List<IrFunction.IrParam> params = List.of(new IrFunction.IrParam(eh.parameterName(), eh.parameterType()));
        localIndices = new HashMap<>();
        localIndices.put(eh.parameterName(), 0);
        List<IrInstruction> instrs = new ArrayList<>();
        int localCount = 1;
        int maxStack = 1;

        if (eh.body() != null) {
            int[] depth = buildBlock(eh.body(), instrs, localCount);
            localCount = depth[0];
            maxStack = depth[1];
        }
        if (instrs.isEmpty() || !(instrs.get(instrs.size() - 1) instanceof IrInstruction.Return)) {
            instrs.add(new IrInstruction.Return());
        }

        IrBlock block = new IrBlock(0, instrs, List.of(), List.of());
        return new IrFunction(eh.functionName(), eh.functionIndex(), params, VeloraTypes.UNIT,
                eh.suspending(), true, List.of(block), localCount, Math.max(maxStack, 1));
    }

    private int[] buildBlock(BlockNode block, List<IrInstruction> instrs, int nextLocal) {
        int localCount = nextLocal;
        int maxStack = 0;
        for (StatementNode stmt : block.statements()) {
            int[] r = buildStatement(stmt, instrs, localCount);
            localCount = r[0];
            maxStack = Math.max(maxStack, r[1]);
        }
        return new int[]{localCount, maxStack};
    }

    private int[] buildStatement(StatementNode stmt, List<IrInstruction> instrs, int nextLocal) {
        int localCount = nextLocal;
        int maxStack = 0;

        if (stmt instanceof VariableDeclarationNode vd) {
            if (vd.initializer() != null) {
                int[] r = buildExpression(vd.initializer(), instrs);
                maxStack = Math.max(maxStack, r[1]);
            }
            int localIdx = localCount;
            localIndices.put(vd.name(), localIdx);
            instrs.add(new IrInstruction.StoreLocal(localIdx, vd.declaredType() != null ? resolveType(vd.declaredType()) : VeloraTypes.UNIT));
            localCount++;
        } else if (stmt instanceof IfStatementNode iff) {
            int[] condDepth = buildExpression(iff.condition(), instrs);
            maxStack = Math.max(maxStack, condDepth[1]);
            int jumpToElseIdx = instrs.size();
            instrs.add(new IrInstruction.JumpIfFalse(0));
            if (iff.thenBlock() != null) {
                int[] r = buildBlock(iff.thenBlock(), instrs, localCount);
                localCount = r[0];
                maxStack = Math.max(maxStack, r[1]);
            }
            int jumpToEndIdx = -1;
            if (iff.elseBlock() != null) {
                jumpToEndIdx = instrs.size();
                instrs.add(new IrInstruction.Jump(0));
            }
            int elseStart = instrs.size();
            if (iff.elseBlock() != null) {
                int[] r = buildBlock(iff.elseBlock(), instrs, localCount);
                localCount = r[0];
                maxStack = Math.max(maxStack, r[1]);
            }
            int endIdx = instrs.size();
            instrs.set(jumpToElseIdx, new IrInstruction.JumpIfFalse(elseStart));
            if (jumpToEndIdx >= 0) instrs.set(jumpToEndIdx, new IrInstruction.Jump(endIdx));
        } else if (stmt instanceof WhileStatementNode ws) {
            int loopStart = instrs.size();
            int[] condDepth = buildExpression(ws.condition(), instrs);
            maxStack = Math.max(maxStack, condDepth[1]);
            int jumpToEndIdx = instrs.size();
            instrs.add(new IrInstruction.JumpIfFalse(0));
            if (ws.body() != null) {
                int[] r = buildBlock(ws.body(), instrs, localCount);
                localCount = r[0];
                maxStack = Math.max(maxStack, r[1]);
            }
            instrs.add(new IrInstruction.Jump(loopStart));
            int endIdx = instrs.size();
            instrs.set(jumpToEndIdx, new IrInstruction.JumpIfFalse(endIdx));
        } else if (stmt instanceof ForStatementNode fs) {
            int[] iterDepth = buildExpression(fs.iterable(), instrs);
            maxStack = Math.max(maxStack, iterDepth[1]);
            int iterLocal = localCount++;
            instrs.add(new IrInstruction.StoreLocal(iterLocal, VeloraTypes.UNIT));
            int indexLocal = localCount++;
            instrs.add(new IrInstruction.Const(new IrValue.IntVal(0)));
            instrs.add(new IrInstruction.StoreLocal(indexLocal, VeloraTypes.UNIT));
            int varLocal = localCount++;
            localIndices.put(fs.variable(), varLocal);
            int loopStart = instrs.size();
            instrs.add(new IrInstruction.LoadLocal(indexLocal, VeloraTypes.UNIT));
            instrs.add(new IrInstruction.LoadLocal(iterLocal, VeloraTypes.UNIT));
            instrs.add(new IrInstruction.GetMember("size", VeloraTypes.INT));
            instrs.add(new IrInstruction.Compare("<"));
            int jumpToEndIdx = instrs.size();
            instrs.add(new IrInstruction.JumpIfFalse(0));
            instrs.add(new IrInstruction.LoadLocal(iterLocal, VeloraTypes.UNIT));
            instrs.add(new IrInstruction.LoadLocal(indexLocal, VeloraTypes.UNIT));
            instrs.add(new IrInstruction.GetIndex(VeloraTypes.UNIT));
            instrs.add(new IrInstruction.StoreLocal(varLocal, VeloraTypes.UNIT));
            int[] r = buildBlock(fs.body(), instrs, localCount);
            localCount = r[0];
            maxStack = Math.max(maxStack, r[1]);
            instrs.add(new IrInstruction.LoadLocal(indexLocal, VeloraTypes.UNIT));
            instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
            instrs.add(new IrInstruction.BinaryOp("+", VeloraTypes.UNIT));
            instrs.add(new IrInstruction.StoreLocal(indexLocal, VeloraTypes.UNIT));
            instrs.add(new IrInstruction.Jump(loopStart));
            int endIdx = instrs.size();
            instrs.set(jumpToEndIdx, new IrInstruction.JumpIfFalse(endIdx));
            maxStack = Math.max(maxStack, 3);
        } else if (stmt instanceof ReturnStatementNode rs) {
            if (rs.value() != null) {
                int[] r = buildExpression(rs.value(), instrs);
                maxStack = Math.max(maxStack, r[1]);
            }
            instrs.add(new IrInstruction.Return());
        } else if (stmt instanceof ExpressionStatementNode es) {
            int[] r = buildExpression(es.expression(), instrs);
            maxStack = Math.max(maxStack, r[1]);
            instrs.add(new IrInstruction.Pop());
        }

        return new int[]{localCount, maxStack};
    }

    private int[] buildExpression(ExpressionNode expr, List<IrInstruction> instrs) {
        int maxStack = 1;

        if (expr instanceof LiteralExpressionNode lit) {
            instrs.add(new IrInstruction.Const(literalToValue(lit)));
        } else if (expr instanceof IdentifierExpressionNode id) {
            Integer localIdx = localIndices.get(id.name());
            if (localIdx != null) {
                instrs.add(new IrInstruction.LoadLocal(localIdx, VeloraTypes.UNIT));
            } else if (settingIndexMap.containsKey(id.name())) {
                instrs.add(new IrInstruction.LoadSetting(settingIndexMap.get(id.name()), VeloraTypes.UNIT));
            } else {
                ResolvedScript.ResolvedProperty prop = resolved.properties().get(id.name());
                if (prop != null) {
                    if (prop.isConst() && prop.constValue() != null) {
                        instrs.add(new IrInstruction.Const(objectToValue(prop.constValue())));
                    } else if (prop.isStatic()) {
                        instrs.add(new IrInstruction.LoadStatic(prop.fieldIndex(), prop.type()));
                    } else {
                        instrs.add(new IrInstruction.LoadField(prop.fieldIndex(), prop.type()));
                    }
                } else {
                    instrs.add(new IrInstruction.LoadLocal(0, VeloraTypes.UNIT));
                }
            }
        } else if (expr instanceof BinaryExpressionNode bin) {
            if (bin.operator().equals("&&")) {
                int[] l = buildExpression(bin.left(), instrs);
                instrs.add(new IrInstruction.Dup());
                int jumpIdx = instrs.size();
                instrs.add(new IrInstruction.JumpIfFalse(0));
                instrs.add(new IrInstruction.Pop());
                int[] r = buildExpression(bin.right(), instrs);
                maxStack = Math.max(maxStack, Math.max(l[1], r[1] + 1));
                int endIdx = instrs.size();
                instrs.set(jumpIdx, new IrInstruction.JumpIfFalse(endIdx));
            } else if (bin.operator().equals("||")) {
                int[] l = buildExpression(bin.left(), instrs);
                instrs.add(new IrInstruction.Dup());
                int jumpIdx = instrs.size();
                instrs.add(new IrInstruction.JumpIfTrue(0));
                instrs.add(new IrInstruction.Pop());
                int[] r = buildExpression(bin.right(), instrs);
                maxStack = Math.max(maxStack, Math.max(l[1], r[1] + 1));
                int endIdx = instrs.size();
                instrs.set(jumpIdx, new IrInstruction.JumpIfTrue(endIdx));
            } else {
                int[] l = buildExpression(bin.left(), instrs);
                int[] r = buildExpression(bin.right(), instrs);
                maxStack = Math.max(maxStack, Math.max(l[1], r[1] + 1));
                if (isComparison(bin.operator())) {
                    instrs.add(new IrInstruction.Compare(bin.operator()));
                } else {
                    instrs.add(new IrInstruction.BinaryOp(bin.operator(), VeloraTypes.UNIT));
                }
            }
        } else if (expr instanceof UnaryExpressionNode un) {
            buildExpression(un.operand(), instrs);
            instrs.add(new IrInstruction.UnaryOp(un.operator(), VeloraTypes.UNIT));
        } else if (expr instanceof CallExpressionNode call) {
            if (call.callee() instanceof IdentifierExpressionNode id) {
                if (id.name().equals("delay") && call.arguments().size() == 1) {
                    int[] r = buildExpression(call.arguments().get(0), instrs);
                    maxStack = Math.max(maxStack, r[1]);
                    instrs.add(new IrInstruction.Delay());
                    instrs.add(new IrInstruction.Const(new IrValue.NullVal()));
                } else if (id.name().equals("yield") && call.arguments().isEmpty()) {
                    instrs.add(new IrInstruction.Yield());
                    instrs.add(new IrInstruction.Const(new IrValue.NullVal()));
                } else if (id.name().equals("await") && call.arguments().size() == 1) {
                    int[] r = buildExpression(call.arguments().get(0), instrs);
                    maxStack = Math.max(maxStack, r[1]);
                    instrs.add(new IrInstruction.Await());
                } else {
                    int argCount = buildCall(call.callee(), call.arguments(), instrs);
                    maxStack = Math.max(maxStack, argCount);
                }
            } else {
                int argCount = buildCall(call.callee(), call.arguments(), instrs);
                maxStack = Math.max(maxStack, argCount);
            }
        } else if (expr instanceof MemberAccessExpressionNode mem) {
            if (mem.target() instanceof IdentifierExpressionNode id && isApiNamespace(id.name())) {
                int apiIdx = resolveApiIndex(id.name(), mem.member());
                if (isApiSuspending(id.name(), mem.member())) {
                    instrs.add(new IrInstruction.CallSuspend(apiIdx, 0, VeloraTypes.UNIT));
                } else {
                    instrs.add(new IrInstruction.CallApi(apiIdx, 0, VeloraTypes.UNIT));
                }
            } else {
                buildExpression(mem.target(), instrs);
                instrs.add(new IrInstruction.GetMember(mem.member(), VeloraTypes.UNIT));
            }
        } else if (expr instanceof QualifiedExpressionNode q) {
            instrs.add(new IrInstruction.Const(new IrValue.StringVal(q.qualifier() + "." + q.member())));
        } else if (expr instanceof ElvisExpressionNode el) {
            buildExpression(el.left(), instrs);
            instrs.add(new IrInstruction.Dup());
            instrs.add(new IrInstruction.IsNull());
            int jumpIdx = instrs.size();
            instrs.add(new IrInstruction.JumpIfFalse(0));
            instrs.add(new IrInstruction.Pop());
            buildExpression(el.right(), instrs);
            int endIdx = instrs.size();
            instrs.set(jumpIdx, new IrInstruction.JumpIfFalse(endIdx));
        } else if (expr instanceof IsExpressionNode is) {
            buildExpression(is.operand(), instrs);
            instrs.add(new IrInstruction.Compare("is"));
        } else if (expr instanceof ListLiteralExpressionNode list) {
            int elemMax = 0;
            for (ExpressionNode e : list.elements()) {
                int[] r = buildExpression(e, instrs);
                elemMax = Math.max(elemMax, r[1]);
            }
            maxStack = Math.max(maxStack, list.elements().size());
            instrs.add(new IrInstruction.CreateList(list.elements().size(), VeloraTypes.UNIT));
        } else if (expr instanceof MapLiteralExpressionNode map) {
            for (var entry : map.entries()) {
                buildExpression(entry.getKey(), instrs);
                buildExpression(entry.getValue(), instrs);
            }
            maxStack = Math.max(maxStack, map.entries().size() * 2);
            instrs.add(new IrInstruction.CreateMap(map.entries().size(), VeloraTypes.UNIT, VeloraTypes.UNIT));
        } else if (expr instanceof InterpolationExpressionNode interp) {
            boolean first = true;
            for (InterpolationExpressionNode.Segment seg : interp.segments()) {
                if (seg instanceof InterpolationExpressionNode.Text t) {
                    instrs.add(new IrInstruction.Const(new IrValue.StringVal(t.value())));
                } else if (seg instanceof InterpolationExpressionNode.Expr e) {
                    buildExpression(e.expression(), instrs);
                    instrs.add(new IrInstruction.Const(new IrValue.StringVal("")));
                    instrs.add(new IrInstruction.BinaryOp("+", VeloraTypes.STRING));
                }
                if (!first) {
                    instrs.add(new IrInstruction.BinaryOp("+", VeloraTypes.STRING));
                }
                first = false;
            }
            maxStack = Math.max(maxStack, 3);
        } else if (expr instanceof DurationExpressionNode dur) {
            buildExpression(dur.amount(), instrs);
        } else if (expr instanceof IndexExpressionNode idx) {
            buildExpression(idx.receiver(), instrs);
            int[] r = buildExpression(idx.index(), instrs);
            maxStack = Math.max(maxStack, r[1] + 1);
            instrs.add(new IrInstruction.GetIndex(VeloraTypes.UNIT));
        } else if (expr instanceof AssignmentExpressionNode assign) {
            String op = assign.operator();
            if (assign.target() instanceof IdentifierExpressionNode id) {
                Integer localIdx = localIndices.get(id.name());
                if (localIdx != null) {
                    if ("++".equals(op)) {
                        instrs.add(new IrInstruction.LoadLocal(localIdx, VeloraTypes.UNIT));
                        instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
                        instrs.add(new IrInstruction.BinaryOp("+", VeloraTypes.UNIT));
                        instrs.add(new IrInstruction.Dup());
                        instrs.add(new IrInstruction.StoreLocal(localIdx, VeloraTypes.UNIT));
                        maxStack = Math.max(maxStack, 2);
                    } else if ("--".equals(op)) {
                        instrs.add(new IrInstruction.LoadLocal(localIdx, VeloraTypes.UNIT));
                        instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
                        instrs.add(new IrInstruction.BinaryOp("-", VeloraTypes.UNIT));
                        instrs.add(new IrInstruction.Dup());
                        instrs.add(new IrInstruction.StoreLocal(localIdx, VeloraTypes.UNIT));
                        maxStack = Math.max(maxStack, 2);
                    } else if (op != null && op.length() > 1 && op.endsWith("=")) {
                        String baseOp = op.substring(0, op.length() - 1);
                        instrs.add(new IrInstruction.LoadLocal(localIdx, VeloraTypes.UNIT));
                        int[] r = buildExpression(assign.value(), instrs);
                        maxStack = Math.max(maxStack, r[1] + 1);
                        instrs.add(new IrInstruction.BinaryOp(baseOp, VeloraTypes.UNIT));
                        instrs.add(new IrInstruction.Dup());
                        instrs.add(new IrInstruction.StoreLocal(localIdx, VeloraTypes.UNIT));
                    } else {
                        int[] r = new int[]{0, 0};
                        if (assign.value() != null) {
                            r = buildExpression(assign.value(), instrs);
                        }
                        maxStack = Math.max(maxStack, Math.max(r[1], 2));
                        instrs.add(new IrInstruction.Dup());
                        instrs.add(new IrInstruction.StoreLocal(localIdx, VeloraTypes.UNIT));
                    }
                } else {
                    ResolvedScript.ResolvedProperty prop = resolved.properties().get(id.name());
                    if (prop != null && prop.isStatic()) {
                        if ("++".equals(op)) {
                            instrs.add(new IrInstruction.LoadStatic(prop.fieldIndex(), prop.type()));
                            instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
                            instrs.add(new IrInstruction.BinaryOp("+", VeloraTypes.UNIT));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreStatic(prop.fieldIndex(), prop.type()));
                            maxStack = Math.max(maxStack, 2);
                        } else if ("--".equals(op)) {
                            instrs.add(new IrInstruction.LoadStatic(prop.fieldIndex(), prop.type()));
                            instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
                            instrs.add(new IrInstruction.BinaryOp("-", VeloraTypes.UNIT));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreStatic(prop.fieldIndex(), prop.type()));
                            maxStack = Math.max(maxStack, 2);
                        } else if (op != null && op.length() > 1 && op.endsWith("=")) {
                            String baseOp = op.substring(0, op.length() - 1);
                            instrs.add(new IrInstruction.LoadStatic(prop.fieldIndex(), prop.type()));
                            int[] r = buildExpression(assign.value(), instrs);
                            maxStack = Math.max(maxStack, r[1] + 1);
                            instrs.add(new IrInstruction.BinaryOp(baseOp, VeloraTypes.UNIT));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreStatic(prop.fieldIndex(), prop.type()));
                        } else {
                            int[] r = new int[]{0, 0};
                            if (assign.value() != null) {
                                r = buildExpression(assign.value(), instrs);
                            }
                            maxStack = Math.max(maxStack, Math.max(r[1], 2));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreStatic(prop.fieldIndex(), prop.type()));
                        }
                    } else if (prop != null) {
                        if ("++".equals(op)) {
                            instrs.add(new IrInstruction.LoadField(prop.fieldIndex(), prop.type()));
                            instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
                            instrs.add(new IrInstruction.BinaryOp("+", VeloraTypes.UNIT));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreField(prop.fieldIndex(), prop.type()));
                            maxStack = Math.max(maxStack, 2);
                        } else if ("--".equals(op)) {
                            instrs.add(new IrInstruction.LoadField(prop.fieldIndex(), prop.type()));
                            instrs.add(new IrInstruction.Const(new IrValue.IntVal(1)));
                            instrs.add(new IrInstruction.BinaryOp("-", VeloraTypes.UNIT));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreField(prop.fieldIndex(), prop.type()));
                            maxStack = Math.max(maxStack, 2);
                        } else if (op != null && op.length() > 1 && op.endsWith("=")) {
                            String baseOp = op.substring(0, op.length() - 1);
                            instrs.add(new IrInstruction.LoadField(prop.fieldIndex(), prop.type()));
                            int[] r = buildExpression(assign.value(), instrs);
                            maxStack = Math.max(maxStack, r[1] + 1);
                            instrs.add(new IrInstruction.BinaryOp(baseOp, VeloraTypes.UNIT));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreField(prop.fieldIndex(), prop.type()));
                        } else {
                            int[] r = new int[]{0, 0};
                            if (assign.value() != null) {
                                r = buildExpression(assign.value(), instrs);
                            }
                            maxStack = Math.max(maxStack, Math.max(r[1], 2));
                            instrs.add(new IrInstruction.Dup());
                            instrs.add(new IrInstruction.StoreField(prop.fieldIndex(), prop.type()));
                        }
                    } else {
                        if (assign.value() != null) {
                            buildExpression(assign.value(), instrs);
                        }
                        instrs.add(new IrInstruction.Pop());
                    }
                }
            } else {
                if (assign.value() != null) {
                    buildExpression(assign.value(), instrs);
                }
                instrs.add(new IrInstruction.Pop());
            }
            maxStack = Math.max(maxStack, 1);
        } else if (expr instanceof SpawnExpressionNode spawn) {
            int funcIdx = resolveFunctionIndex(spawn.callee());
            for (ExpressionNode a : spawn.arguments()) {
                buildExpression(a, instrs);
            }
            instrs.add(new IrInstruction.Spawn(funcIdx, spawn.arguments().size()));
        }

        return new int[]{0, maxStack};
    }

    private int buildCall(ExpressionNode callee, List<ExpressionNode> args, List<IrInstruction> instrs) {
        String funcName = null;
        if (callee instanceof IdentifierExpressionNode id) funcName = id.name();
        ResolvedScript.ResolvedFunction rf = funcName != null ? resolved.functions().get(funcName) : null;

        boolean hasNamedArgs = args.stream().anyMatch(a -> a instanceof NamedArgumentExpressionNode);

        if (rf != null && (hasNamedArgs || (args.size() < rf.parameters().size()))) {
            int paramCount = rf.parameters().size();
            int callMaxStack = 0;
            for (int i = 0; i < paramCount; i++) {
                ResolvedScript.ResolvedParam param = rf.parameters().get(i);
                ExpressionNode matched = null;
                for (ExpressionNode a : args) {
                    if (a instanceof NamedArgumentExpressionNode named && named.argumentName().equals(param.name())) {
                        matched = named.value();
                        break;
                    }
                }
                if (matched == null && i < args.size() && !(args.get(i) instanceof NamedArgumentExpressionNode)) {
                    matched = args.get(i);
                }
                if (matched != null) {
                    int[] r = buildExpression(matched, instrs);
                    callMaxStack = Math.max(callMaxStack, r[1] + i);
                } else if (param.hasDefault()) {
                    instrs.add(new IrInstruction.Const(objectToValue(param.defaultValue())));
                    callMaxStack = Math.max(callMaxStack, 1 + i);
                } else {
                    instrs.add(new IrInstruction.Const(new IrValue.NullVal()));
                    callMaxStack = Math.max(callMaxStack, 1 + i);
                }
            }
            if (callee instanceof IdentifierExpressionNode id) {
                int funcIdx = resolveFunctionIndex(id);
                instrs.add(new IrInstruction.Call(funcIdx, paramCount, VeloraTypes.UNIT));
            } else {
                instrs.add(new IrInstruction.Call(0, paramCount, VeloraTypes.UNIT));
            }
            return Math.max(callMaxStack, paramCount);
        } else {
            int callMaxStack = 0;
            int argIdx = 0;
            for (ExpressionNode a : args) {
                if (a instanceof NamedArgumentExpressionNode named) {
                    int[] r = buildExpression(named.value(), instrs);
                    callMaxStack = Math.max(callMaxStack, r[1] + argIdx);
                } else {
                    int[] r = buildExpression(a, instrs);
                    callMaxStack = Math.max(callMaxStack, r[1] + argIdx);
                }
                argIdx++;
            }
            if (callee instanceof MemberAccessExpressionNode mem) {
                if (mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
                    int apiIdx = resolveApiIndex(ns.name(), mem.member());
                    // Check if the API function is suspending
                    boolean isSuspending = isApiSuspending(ns.name(), mem.member());
                    if (isSuspending) {
                        instrs.add(new IrInstruction.CallSuspend(apiIdx, args.size(), VeloraTypes.UNIT));
                    } else {
                        instrs.add(new IrInstruction.CallApi(apiIdx, args.size(), VeloraTypes.UNIT));
                    }
                } else {
                    instrs.add(new IrInstruction.CallApi(0, args.size(), VeloraTypes.UNIT));
                }
            } else if (callee instanceof IdentifierExpressionNode id) {
                int funcIdx = resolveFunctionIndex(id);
                instrs.add(new IrInstruction.Call(funcIdx, args.size(), VeloraTypes.UNIT));
            } else {
                instrs.add(new IrInstruction.Call(0, args.size(), VeloraTypes.UNIT));
            }
            return Math.max(callMaxStack, args.size());
        }
    }

    private int resolveFunctionIndex(ExpressionNode callee) {
        String name = null;
        if (callee instanceof IdentifierExpressionNode id) {
            name = id.name();
        } else if (callee instanceof MemberAccessExpressionNode mem) {
            name = mem.member();
        }
        if (name != null) {
            ResolvedScript.ResolvedFunction rf = resolved.functions().get(name);
            if (rf != null) return rf.functionIndex();
        }
        return 0;
    }

    private int resolveApiIndex(String namespace, String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        if (reg == null) return 0;
        List<FunctionDescriptor> all = reg.all();
        for (int i = 0; i < all.size(); i++) {
            FunctionDescriptor fd = all.get(i);
            if (fd.namespace().equals(namespace) && fd.name().equals(name)) {
                return i;
            }
        }
        return 0;
    }

    private boolean isApiSuspending(String namespace, String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        if (reg == null) return false;
        List<FunctionDescriptor> all = reg.all();
        for (FunctionDescriptor fd : all) {
            if (fd.namespace().equals(namespace) && fd.name().equals(name)) {
                return fd.suspending();
            }
        }
        return false;
    }

    private boolean isApiNamespace(String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        return reg != null && reg.namespaces().contains(name);
    }

    private IrValue literalToValue(LiteralExpressionNode lit) {
        return switch (lit.kind()) {
            case INTEGER -> new IrValue.IntVal((Integer) lit.value());
            case LONG -> new IrValue.LongVal((Long) lit.value());
            case FLOAT -> new IrValue.FloatVal((Float) lit.value());
            case DOUBLE -> new IrValue.DoubleVal((Double) lit.value());
            case STRING -> new IrValue.StringVal((String) lit.value());
            case BOOLEAN -> new IrValue.BooleanVal((Boolean) lit.value());
            case NULL -> new IrValue.NullVal();
        };
    }

    private boolean isComparison(String op) {
        return op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=")
                || op.equals(">") || op.equals(">=") || op.equals("is");
    }

    private VeloraType resolveType(TypeNode typeNode) {
        if (typeNode == null) return VeloraTypes.UNIT;
        String name = typeNode.typeName();
        if (name == null) return VeloraTypes.UNIT;
        return switch (name) {
            case "Int", "int" -> VeloraTypes.INT;
            case "Long", "long" -> VeloraTypes.LONG;
            case "Float", "float" -> VeloraTypes.FLOAT;
            case "Double", "double" -> VeloraTypes.DOUBLE;
            case "Boolean", "boolean", "bool" -> VeloraTypes.BOOLEAN;
            case "String" -> VeloraTypes.STRING;
            case "Byte", "byte" -> VeloraTypes.BYTE;
            case "Char", "char" -> VeloraTypes.CHAR;
            case "Unit", "void" -> VeloraTypes.UNIT;
            case "Duration" -> VeloraTypes.DURATION;
            default -> {
                if (!typeNode.typeArguments().isEmpty()) {
                    if (name.equals("List") || name.equals("list")) {
                        yield VeloraTypes.list(resolveType(typeNode.typeArguments().get(0)));
                    } else if (name.equals("Map") || name.equals("map")) {
                        yield VeloraTypes.map(resolveType(typeNode.typeArguments().get(0)), resolveType(typeNode.typeArguments().get(1)));
                    }
                }
                yield VeloraTypes.UNIT;
            }
        };
    }

    private IrValue objectToValue(Object value) {
        if (value == null) return new IrValue.NullVal();
        if (value instanceof Integer i) return new IrValue.IntVal(i);
        if (value instanceof Long l) return new IrValue.LongVal(l);
        if (value instanceof Float f) return new IrValue.FloatVal(f);
        if (value instanceof Double d) return new IrValue.DoubleVal(d);
        if (value instanceof Boolean b) return new IrValue.BooleanVal(b);
        if (value instanceof String s) return new IrValue.StringVal(s);
        return new IrValue.NullVal();
    }
}
