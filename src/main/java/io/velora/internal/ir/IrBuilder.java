package io.velora.internal.ir;

import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.ast.*;
import io.velora.internal.semantic.ResolvedScript;

import java.util.*;

public final class IrBuilder {

    private final ResolvedScript resolved;
    private final ApiRegistry apiRegistry;
    private final ConstantRegistry constantRegistry;
    private final TypeRegistry typeRegistry;
    private final String scriptId;
    private Map<String, Integer> localIndices;
    private final Map<String, Integer> settingIndexMap = new HashMap<>();

    public IrBuilder(ResolvedScript resolved) {
        this(resolved, null, null, null, null);
    }

    public IrBuilder(ResolvedScript resolved, ApiRegistry apiRegistry) {
        this(resolved, apiRegistry, null, null, null);
    }

    public IrBuilder(ResolvedScript resolved, ApiRegistry apiRegistry, ConstantRegistry constantRegistry, TypeRegistry typeRegistry) {
        this(resolved, apiRegistry, constantRegistry, typeRegistry, null);
    }

    public IrBuilder(ResolvedScript resolved, ApiRegistry apiRegistry, ConstantRegistry constantRegistry, TypeRegistry typeRegistry, String scriptId) {
        this.resolved = Objects.requireNonNull(resolved);
        this.apiRegistry = apiRegistry;
        this.constantRegistry = constantRegistry;
        this.typeRegistry = typeRegistry;
        this.scriptId = scriptId != null ? scriptId : resolved.metadata().id();
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
                        prop.fieldIndex(), prop.isStatic(), ScriptValue.fromJava(prop.constValue())));
            }
        }

        Map<Integer, IrFunction> functionsByIndex = new TreeMap<>();
        for (ResolvedScript.ResolvedFunction rf : resolved.functions().values()) {
            functionsByIndex.put(rf.functionIndex(), buildFunction(rf));
        }
        for (var entry : resolved.lifecycle().entrySet()) {
            ResolvedScript.ResolvedFunction function = entry.getValue();
            IrFunction built = buildFunction(function);
            String hook = entry.getKey().name();
            functionsByIndex.put(function.functionIndex(), new IrFunction(hook, built.index(), built.parameters(), built.returnType(),
                    built.suspending(), true, built.blocks(), built.localCount(), built.maxStack()));
            lifecycleHooks.add(hook);
        }
        for (ResolvedScript.ResolvedEventHandler eh : resolved.eventHandlers()) {
            functionsByIndex.put(eh.functionIndex(), buildEventHandler(eh));
            eventHandlers.add(new IrModule.EventHandlerInfo(
                    eh.eventReference(), eh.functionName(), eh.functionIndex(), eh.suspending()));
        }
        functions.addAll(functionsByIndex.values());

        return new IrModule(
                scriptId,
                resolved.metadata().name(),
                resolved.metadata().version(),
                resolved.languageVersion(),
                functions,
                resolved.settings(),
                persistentFieldIds,
                persistentFieldTypes,
                persistentFieldIndices,
                persistentFieldIsStatic,
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
        List<IrFunction.IrParam> params = eh.parameterName() == null
                ? List.of()
                : List.of(new IrFunction.IrParam(eh.parameterName(), eh.parameterType()));
        localIndices = new HashMap<>();
        if (eh.parameterName() != null) localIndices.put(eh.parameterName(), 0);
        List<IrInstruction> instrs = new ArrayList<>();
        int localCount = params.size();
        int maxStack = params.isEmpty() ? 0 : 1;

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
        Map<String, Integer> previous = new HashMap<>(localIndices);
        int localCount = nextLocal;
        int maxStack = 0;
        try {
            for (StatementNode stmt : block.statements()) {
                int[] r = buildStatement(stmt, instrs, localCount);
                localCount = r[0];
                maxStack = Math.max(maxStack, r[1]);
            }
            return new int[]{localCount, maxStack};
        } finally {
            localIndices.clear();
            localIndices.putAll(previous);
        }
    }

    private int[] buildStatement(StatementNode stmt, List<IrInstruction> instrs, int nextLocal) {
        int localCount = nextLocal;
        int maxStack = 0;
        if (stmt.line() > 0) instrs.add(new IrInstruction.Line(stmt.line()));

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
            Integer previousLoopLocal = localIndices.put(fs.variable(), varLocal);
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
            if (previousLoopLocal == null) localIndices.remove(fs.variable());
            else localIndices.put(fs.variable(), previousLoopLocal);
            maxStack = Math.max(maxStack, 3);
        } else if (stmt instanceof WhenStatementNode when) {
            int[] subjectDepth = buildExpression(when.subject(), instrs);
            maxStack = Math.max(maxStack, subjectDepth[1]);
            int subjectLocal = localCount++;
            instrs.add(new IrInstruction.StoreLocal(subjectLocal, VeloraTypes.UNIT));
            List<Integer> endJumps = new ArrayList<>();
            for (WhenStatementNode.Case c : when.cases()) {
                List<Integer> caseJumps = new ArrayList<>();
                for (ExpressionNode condition : c.conditions()) {
                    instrs.add(new IrInstruction.LoadLocal(subjectLocal, VeloraTypes.UNIT));
                    int[] conditionDepth = buildExpression(condition, instrs);
                    maxStack = Math.max(maxStack, Math.max(2, conditionDepth[1] + 1));
                    instrs.add(new IrInstruction.Compare("=="));
                    caseJumps.add(instrs.size());
                    instrs.add(new IrInstruction.JumpIfTrue(0));
                }
                int skipCase = instrs.size();
                instrs.add(new IrInstruction.Jump(0));
                int caseStart = instrs.size();
                for (int jump : caseJumps) instrs.set(jump, new IrInstruction.JumpIfTrue(caseStart));
                int[] bodyDepth = buildBlock(c.body(), instrs, localCount);
                localCount = bodyDepth[0];
                maxStack = Math.max(maxStack, bodyDepth[1]);
                endJumps.add(instrs.size());
                instrs.add(new IrInstruction.Jump(0));
                instrs.set(skipCase, new IrInstruction.Jump(instrs.size()));
            }
            if (when.elseBody() != null) {
                int[] bodyDepth = buildBlock(when.elseBody(), instrs, localCount);
                localCount = bodyDepth[0];
                maxStack = Math.max(maxStack, bodyDepth[1]);
            }
            int end = instrs.size();
            for (int jump : endJumps) instrs.set(jump, new IrInstruction.Jump(end));
        } else if (stmt instanceof ReturnStatementNode rs) {
            if (rs.value() != null) {
                int[] r = buildExpression(rs.value(), instrs);
                maxStack = Math.max(maxStack, r[1]);
            }
            instrs.add(new IrInstruction.Return());
        } else if (stmt instanceof ExpressionStatementNode es) {
            if (es.expression() instanceof AssignmentExpressionNode assignment
                    && "=".equals(assignment.operator())
                    && assignment.target() instanceof IdentifierExpressionNode id
                    && !localIndices.containsKey(id.name())
                    && !resolved.properties().containsKey(id.name())
                    && !settingIndexMap.containsKey(id.name())) {
                int[] r = buildExpression(assignment.value(), instrs);
                maxStack = Math.max(maxStack, r[1]);
                int localIdx = localCount++;
                localIndices.put(id.name(), localIdx);
                instrs.add(new IrInstruction.StoreLocal(localIdx, VeloraTypes.UNIT));
            } else {
                int[] r = buildExpression(es.expression(), instrs);
                maxStack = Math.max(maxStack, r[1]);
                instrs.add(new IrInstruction.Pop());
            }
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
            int[] operand = buildExpression(un.operand(), instrs);
            maxStack = Math.max(maxStack, operand[1]);
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
            if (mem.target() instanceof IdentifierExpressionNode id && isQualifiedHostValue(id.name(), mem.member())) {
                instrs.add(new IrInstruction.LoadQualified(id.name(), mem.member()));
            } else if (mem.target() instanceof IdentifierExpressionNode id && isApiNamespace(id.name())) {
                FunctionDescriptor descriptor = resolveApiDescriptor(id.name(), mem.member());
                int apiIdx = descriptor.index() >= 0 ? descriptor.index() : resolveApiIndex(id.name(), mem.member());
                if (descriptor.suspending()) {
                    instrs.add(new IrInstruction.CallSuspend(apiIdx, 0, descriptor.returnType()));
                } else {
                    instrs.add(new IrInstruction.CallApi(apiIdx, 0, descriptor.returnType()));
                }
            } else if (mem.isSafeAccess()) {
                int[] targetDepth = buildExpression(mem.target(), instrs);
                maxStack = Math.max(maxStack, Math.max(2, targetDepth[1] + 1));
                instrs.add(new IrInstruction.Dup());
                instrs.add(new IrInstruction.IsNull());
                int memberJump = instrs.size();
                instrs.add(new IrInstruction.JumpIfFalse(0));
                int endJump = instrs.size();
                instrs.add(new IrInstruction.Jump(0));
                int memberStart = instrs.size();
                instrs.set(memberJump, new IrInstruction.JumpIfFalse(memberStart));
                instrs.add(new IrInstruction.GetMember(mem.member(), VeloraTypes.UNIT));
                instrs.set(endJump, new IrInstruction.Jump(instrs.size()));
            } else {
                int[] targetDepth = buildExpression(mem.target(), instrs);
                maxStack = Math.max(maxStack, targetDepth[1]);
                instrs.add(new IrInstruction.GetMember(mem.member(), VeloraTypes.UNIT));
            }
        } else if (expr instanceof QualifiedExpressionNode q) {
            instrs.add(new IrInstruction.LoadQualified(q.qualifier(), q.member()));
        } else if (expr instanceof ElvisExpressionNode el) {
            int[] left = buildExpression(el.left(), instrs);
            instrs.add(new IrInstruction.Dup());
            instrs.add(new IrInstruction.IsNull());
            int jumpIdx = instrs.size();
            instrs.add(new IrInstruction.JumpIfFalse(0));
            instrs.add(new IrInstruction.Pop());
            int[] right = buildExpression(el.right(), instrs);
            int endIdx = instrs.size();
            instrs.set(jumpIdx, new IrInstruction.JumpIfFalse(endIdx));
            maxStack = Math.max(maxStack, Math.max(left[1] + 1, right[1]));
        } else if (expr instanceof IsExpressionNode is) {
            buildExpression(is.operand(), instrs);
            instrs.add(new IrInstruction.IsType(typeName(is.type())));
        } else if (expr instanceof CollectionConstructorExpressionNode collection) {
            switch (collection.kind()) {
                case LIST -> instrs.add(new IrInstruction.CreateList(0, VeloraTypes.UNIT));
                case SET -> instrs.add(new IrInstruction.CreateSet(0, VeloraTypes.UNIT));
                case MAP -> instrs.add(new IrInstruction.CreateMap(0, VeloraTypes.UNIT, VeloraTypes.UNIT));
            }
            maxStack = Math.max(maxStack, 1);
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
            instrs.add(new IrInstruction.Const(new IrValue.DurationVal(durationToNanos(dur))));
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
            if (!(spawn.callee() instanceof IdentifierExpressionNode id)) throw new IllegalStateException("spawn requires a script function");
            ResolvedScript.ResolvedFunction function = resolved.functions().get(id.name());
            if (function == null) throw new IllegalStateException("Unresolved function: " + id.name());
            List<ExpressionNode> bound = bindArguments(spawn.arguments(), function.parameters().stream().map(ResolvedScript.ResolvedParam::name).toList());
            int stack = emitBoundArguments(bound, function.parameters().stream().map(ResolvedScript.ResolvedParam::defaultValue).toList(), instrs);
            maxStack = Math.max(maxStack, Math.max(stack, function.parameters().size()));
            instrs.add(new IrInstruction.Spawn(function.functionIndex(), function.parameters().size()));
        }

        return new int[]{0, maxStack};
    }

    private int buildCall(ExpressionNode callee, List<ExpressionNode> args, List<IrInstruction> instrs) {
        if (callee instanceof IdentifierExpressionNode id) {
            ResolvedScript.ResolvedFunction function = resolved.functions().get(id.name());
            if (function == null) throw new IllegalStateException("Unresolved function: " + id.name());
            List<ExpressionNode> bound = bindArguments(args, function.parameters().stream().map(ResolvedScript.ResolvedParam::name).toList());
            int stack = emitBoundArguments(bound, function.parameters().stream().map(ResolvedScript.ResolvedParam::defaultValue).toList(), instrs);
            instrs.add(new IrInstruction.Call(function.functionIndex(), function.parameters().size(), function.returnType()));
            return Math.max(stack, function.parameters().size());
        }
        if (callee instanceof MemberAccessExpressionNode mem && mem.target() instanceof IdentifierExpressionNode ns && isApiNamespace(ns.name())) {
            FunctionDescriptor descriptor = resolveApiDescriptor(ns.name(), mem.member());
            List<ExpressionNode> bound = bindArguments(args, descriptor.parameters().stream().map(io.velora.api.function.ParameterDescriptor::name).toList());
            List<Object> defaults = descriptor.parameters().stream().map(p -> p.hasDefault() ? p.defaultValue() : null).toList();
            int stack = emitBoundArguments(bound, defaults, instrs);
            int apiIdx = descriptor.index() >= 0 ? descriptor.index() : resolveApiIndex(ns.name(), mem.member());
            if (descriptor.suspending()) instrs.add(new IrInstruction.CallSuspend(apiIdx, descriptor.parameters().size(), descriptor.returnType()));
            else instrs.add(new IrInstruction.CallApi(apiIdx, descriptor.parameters().size(), descriptor.returnType()));
            return Math.max(stack, descriptor.parameters().size());
        }
        if (callee instanceof MemberAccessExpressionNode mem) {
            int[] receiver = buildExpression(mem.target(), instrs);
            int max = receiver[1];
            for (int i = 0; i < args.size(); i++) {
                int[] result = buildExpression(args.get(i), instrs);
                max = Math.max(max, result[1] + i + 1);
            }
            VeloraType resultType = switch (mem.member()) {
                case "contains", "containsKey", "remove" -> VeloraTypes.BOOLEAN;
                default -> VeloraTypes.UNIT;
            };
            instrs.add(new IrInstruction.CallMember(mem.member(), args.size(), resultType));
            return Math.max(max, args.size() + 1);
        }
        throw new IllegalStateException("Unsupported callable expression: " + callee.nodeName());
    }

    private List<ExpressionNode> bindArguments(List<ExpressionNode> args, List<String> parameterNames) {
        List<ExpressionNode> bound = new ArrayList<>(Collections.nCopies(parameterNames.size(), null));
        int positional = 0;
        for (ExpressionNode argument : args) {
            if (argument instanceof NamedArgumentExpressionNode named) {
                int index = parameterNames.indexOf(named.argumentName());
                if (index >= 0) bound.set(index, named.value());
            } else {
                while (positional < bound.size() && bound.get(positional) != null) positional++;
                if (positional < bound.size()) bound.set(positional++, argument);
            }
        }
        return bound;
    }

    private int emitBoundArguments(List<ExpressionNode> bound, List<Object> defaults, List<IrInstruction> instrs) {
        int max = 0;
        for (int i = 0; i < bound.size(); i++) {
            ExpressionNode expression = bound.get(i);
            if (expression != null) {
                int[] result = buildExpression(expression, instrs);
                max = Math.max(max, result[1] + i);
            } else {
                instrs.add(new IrInstruction.Const(objectToValue(defaults.get(i))));
                max = Math.max(max, i + 1);
            }
        }
        return max;
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
        throw new IllegalStateException("Unresolved function: " + (name == null ? callee.nodeName() : name));
    }

    private int resolveApiIndex(String namespace, String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        if (reg == null) throw new IllegalStateException("API registry is unavailable");
        String actual = resolveApiNamespace(namespace);
        List<FunctionDescriptor> all = reg.all();
        for (int i = 0; i < all.size(); i++) {
            FunctionDescriptor fd = all.get(i);
            if (fd.namespace().equals(actual) && fd.name().equals(name)) return i;
        }
        throw new IllegalStateException("Unresolved API function: " + namespace + "." + name);
    }

    private FunctionDescriptor resolveApiDescriptor(String namespace, String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        if (reg == null) throw new IllegalStateException("API registry is unavailable");
        FunctionDescriptor descriptor = reg.find(resolveApiNamespace(namespace), name);
        if (descriptor == null) throw new IllegalStateException("Unresolved API function: " + namespace + "." + name);
        return descriptor;
    }

    private boolean isApiNamespace(String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        return reg != null && (reg.namespaces().contains(name) || resolved.importNamespaces().containsKey(name));
    }

    private String resolveApiNamespace(String name) {
        ApiRegistry reg = apiRegistry != null ? apiRegistry : resolved.apiRegistry();
        if (reg != null && reg.namespaces().contains(name)) return name;
        return resolved.importNamespaces().get(name);
    }

    private boolean isQualifiedHostValue(String namespace, String member) {
        if (constantRegistry != null && constantRegistry.find(namespace, member) != null) return true;
        if (typeRegistry == null) return false;
        VeloraType type = typeRegistry.find(namespace);
        return type instanceof io.velora.api.type.EnumType enumType && enumType.hasConstant(member);
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

    private String typeName(TypeNode type) {
        String base = switch (type.typeName()) {
            case "int" -> "Int";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            case "boolean", "bool" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Char";
            case "void" -> "Unit";
            default -> type.typeName();
        };
        if (!type.typeArguments().isEmpty()) {
            base += "<" + String.join(",", type.typeArguments().stream().map(this::typeName).toList()) + ">";
        }
        return type.nullable() ? base + "?" : base;
    }

    private VeloraType resolveType(TypeNode typeNode) {
        if (typeNode == null || typeNode.typeName() == null) return VeloraTypes.UNIT;
        String name = typeNode.typeName();
        VeloraType type = switch (name) {
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
            case "List", "list" -> typeNode.typeArguments().size() == 1 ? VeloraTypes.list(resolveType(typeNode.typeArguments().get(0))) : VeloraTypes.UNIT;
            case "Set", "set" -> typeNode.typeArguments().size() == 1 ? VeloraTypes.set(resolveType(typeNode.typeArguments().get(0))) : VeloraTypes.UNIT;
            case "Task", "task" -> typeNode.typeArguments().size() == 1 ? VeloraTypes.task(resolveType(typeNode.typeArguments().get(0))) : VeloraTypes.UNIT;
            case "Map", "map" -> typeNode.typeArguments().size() == 2 ? VeloraTypes.map(resolveType(typeNode.typeArguments().get(0)), resolveType(typeNode.typeArguments().get(1))) : VeloraTypes.UNIT;
            default -> VeloraTypes.UNIT;
        };
        return typeNode.nullable() ? type.nullable() : type;
    }

    private long durationToNanos(DurationExpressionNode duration) {
        if (!(duration.amount() instanceof LiteralExpressionNode literal) || !(literal.value() instanceof Number number)) {
            throw new IllegalStateException("Duration amount must be numeric");
        }
        long factor = switch (duration.unit()) {
            case "millis", "milliseconds", "ms" -> 1_000_000L;
            case "seconds", "second", "sec", "s" -> 1_000_000_000L;
            case "minutes", "minute", "min" -> 60_000_000_000L;
            case "hours", "hour", "h" -> 3_600_000_000_000L;
            case "days", "day" -> 86_400_000_000_000L;
            default -> throw new IllegalStateException("Unknown duration unit: " + duration.unit());
        };
        try {
            return new java.math.BigDecimal(number.toString()).multiply(java.math.BigDecimal.valueOf(factor)).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration is out of range or below nanosecond precision", e);
        }
    }

    private IrValue objectToValue(Object value) {
        if (value == null) return new IrValue.NullVal();
        if (value instanceof Short s) return new IrValue.IntVal(s.intValue());
        if (value instanceof Integer i) return new IrValue.IntVal(i);
        if (value instanceof Long l) return new IrValue.LongVal(l);
        if (value instanceof Float f) return new IrValue.FloatVal(f);
        if (value instanceof Double d) return new IrValue.DoubleVal(d);
        if (value instanceof Boolean b) return new IrValue.BooleanVal(b);
        if (value instanceof String s) return new IrValue.StringVal(s);
        if (value instanceof java.time.Duration duration) return new IrValue.DurationVal(duration.toNanos());
        if (value instanceof java.util.UUID uuid) return new IrValue.StringVal(uuid.toString());
        throw new IllegalArgumentException("Unsupported bytecode constant: " + value.getClass().getTypeName());
    }
}
