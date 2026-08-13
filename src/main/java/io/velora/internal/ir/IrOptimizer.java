package io.velora.internal.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IrOptimizer {
    public IrModule optimize(IrModule module) {
        List<IrFunction> functions = new ArrayList<>(module.functions().size());
        for (IrFunction function : module.functions()) functions.add(optimizeFunction(function));
        return new IrModule(module.scriptId(), module.scriptName(), module.version(), module.languageVersion(), functions,
                module.settings(), module.persistentFieldIds(), module.persistentFieldTypes(), module.persistentFieldIndices(),
                module.persistentFieldIsStatic(), module.requiredPermissions(), module.maximumPermissions(), module.lifecycleHooks(),
                module.eventHandlers(), module.fieldInitializers(), module.author(), module.description());
    }

    private IrFunction optimizeFunction(IrFunction function) {
        List<IrInstruction> source = new ArrayList<>();
        for (IrBlock block : function.blocks()) source.addAll(block.instructions());
        if (source.isEmpty()) return function;

        Set<Integer> targets = new HashSet<>();
        for (IrInstruction instruction : source) {
            if (instruction instanceof IrInstruction.Jump jump) targets.add(jump.targetBlock());
            else if (instruction instanceof IrInstruction.JumpIfFalse jump) targets.add(jump.targetBlock());
            else if (instruction instanceof IrInstruction.JumpIfTrue jump) targets.add(jump.targetBlock());
        }

        List<IrInstruction> optimized = new ArrayList<>(source.size());
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < source.size();) {
            remap.put(i, optimized.size());
            IrInstruction replacement = foldBinary(source, targets, i);
            int consumed = replacement != null ? 3 : 0;
            if (replacement == null) {
                replacement = foldUnary(source, targets, i);
                consumed = replacement != null ? 2 : 0;
            }
            if (replacement != null) {
                optimized.add(replacement);
                for (int j = 1; j < consumed; j++) remap.put(i + j, optimized.size() - 1);
                i += consumed;
            } else {
                optimized.add(source.get(i));
                i++;
            }
        }
        remap.put(source.size(), optimized.size());

        for (int i = 0; i < optimized.size(); i++) {
            IrInstruction instruction = optimized.get(i);
            if (instruction instanceof IrInstruction.Jump jump) optimized.set(i, new IrInstruction.Jump(remapTarget(remap, jump.targetBlock())));
            else if (instruction instanceof IrInstruction.JumpIfFalse jump) optimized.set(i, new IrInstruction.JumpIfFalse(remapTarget(remap, jump.targetBlock())));
            else if (instruction instanceof IrInstruction.JumpIfTrue jump) optimized.set(i, new IrInstruction.JumpIfTrue(remapTarget(remap, jump.targetBlock())));
        }

        IrBlock block = new IrBlock(0, optimized, List.of(), List.of());
        return new IrFunction(function.name(), function.index(), function.parameters(), function.returnType(), function.suspending(),
                function.isLifecycle(), List.of(block), function.localCount(), function.maxStack());
    }

    private int remapTarget(Map<Integer, Integer> remap, int target) {
        Integer mapped = remap.get(target);
        return mapped != null ? mapped : target;
    }

    private IrInstruction foldBinary(List<IrInstruction> source, Set<Integer> targets, int index) {
        if (index + 2 >= source.size() || targets.contains(index + 1) || targets.contains(index + 2)) return null;
        if (!(source.get(index) instanceof IrInstruction.Const left) || !(source.get(index + 1) instanceof IrInstruction.Const right)) return null;
        if (source.get(index + 2) instanceof IrInstruction.BinaryOp binary) {
            IrValue value = binary(binary.operator(), left.value(), right.value());
            return value != null ? new IrInstruction.Const(value) : null;
        }
        if (source.get(index + 2) instanceof IrInstruction.Compare compare) {
            IrValue value = compare(compare.operator(), left.value(), right.value());
            return value != null ? new IrInstruction.Const(value) : null;
        }
        return null;
    }

    private IrInstruction foldUnary(List<IrInstruction> source, Set<Integer> targets, int index) {
        if (index + 1 >= source.size() || targets.contains(index + 1) || !(source.get(index) instanceof IrInstruction.Const constant)) return null;
        IrInstruction operator = source.get(index + 1);
        IrValue value = null;
        if (operator instanceof IrInstruction.UnaryOp unary) value = unary(unary.operator(), constant.value());
        else if (operator instanceof IrInstruction.Not) value = boolNot(constant.value());
        else if (operator instanceof IrInstruction.IsNull) value = new IrValue.BooleanVal(constant.value() instanceof IrValue.NullVal);
        return value != null ? new IrInstruction.Const(value) : null;
    }

    private IrValue binary(String operator, IrValue left, IrValue right) {
        if (operator.equals("+") && (left instanceof IrValue.StringVal || right instanceof IrValue.StringVal)) {
            return new IrValue.StringVal(text(left) + text(right));
        }
        if (!numeric(left) || !numeric(right)) return null;
        double divisor = number(right).doubleValue();
        if ((operator.equals("/") || operator.equals("%")) && divisor == 0.0d) return null;
        if (left instanceof IrValue.DoubleVal || right instanceof IrValue.DoubleVal) {
            double a = number(left).doubleValue(), b = divisor;
            return new IrValue.DoubleVal(switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); });
        }
        if (left instanceof IrValue.FloatVal || right instanceof IrValue.FloatVal) {
            float a = number(left).floatValue(), b = number(right).floatValue();
            return new IrValue.FloatVal(switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); });
        }
        if (left instanceof IrValue.LongVal || right instanceof IrValue.LongVal || left instanceof IrValue.DurationVal || right instanceof IrValue.DurationVal) {
            long a = number(left).longValue(), b = number(right).longValue();
            return new IrValue.LongVal(switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); });
        }
        int a = number(left).intValue(), b = number(right).intValue();
        return new IrValue.IntVal(switch (operator) { case "+" -> a + b; case "-" -> a - b; case "*" -> a * b; case "/" -> a / b; case "%" -> a % b; default -> throw new IllegalArgumentException(); });
    }

    private IrValue compare(String operator, IrValue left, IrValue right) {
        boolean result;
        if (operator.equals("==") || operator.equals("!=")) {
            boolean equal;
            if (numeric(left) && numeric(right)) {
                Number a = number(left), b = number(right);
                equal = left instanceof IrValue.FloatVal || left instanceof IrValue.DoubleVal || right instanceof IrValue.FloatVal || right instanceof IrValue.DoubleVal
                        ? Double.compare(a.doubleValue(), b.doubleValue()) == 0 : a.longValue() == b.longValue();
            } else equal = left.equals(right);
            result = operator.equals("==") ? equal : !equal;
        } else if (numeric(left) && numeric(right)) {
            int comparison = Double.compare(number(left).doubleValue(), number(right).doubleValue());
            result = switch (operator) { case "<" -> comparison < 0; case "<=" -> comparison <= 0; case ">" -> comparison > 0; case ">=" -> comparison >= 0; default -> false; };
        } else if (left instanceof IrValue.StringVal a && right instanceof IrValue.StringVal b) {
            int comparison = a.value().compareTo(b.value());
            result = switch (operator) { case "<" -> comparison < 0; case "<=" -> comparison <= 0; case ">" -> comparison > 0; case ">=" -> comparison >= 0; default -> false; };
        } else return null;
        return new IrValue.BooleanVal(result);
    }

    private IrValue unary(String operator, IrValue value) {
        if (operator.equals("!")) return boolNot(value);
        if (!operator.equals("-") || !numeric(value)) return null;
        if (value instanceof IrValue.DoubleVal v) return new IrValue.DoubleVal(-v.value());
        if (value instanceof IrValue.FloatVal v) return new IrValue.FloatVal(-v.value());
        if (value instanceof IrValue.LongVal v) return new IrValue.LongVal(-v.value());
        if (value instanceof IrValue.DurationVal v) return new IrValue.DurationVal(-v.nanos());
        return new IrValue.IntVal(-number(value).intValue());
    }

    private IrValue boolNot(IrValue value) {
        return value instanceof IrValue.BooleanVal booleanValue ? new IrValue.BooleanVal(!booleanValue.value()) : null;
    }

    private boolean numeric(IrValue value) {
        return value instanceof IrValue.IntVal || value instanceof IrValue.LongVal || value instanceof IrValue.FloatVal || value instanceof IrValue.DoubleVal || value instanceof IrValue.DurationVal;
    }

    private Number number(IrValue value) {
        if (value instanceof IrValue.IntVal v) return v.value();
        if (value instanceof IrValue.LongVal v) return v.value();
        if (value instanceof IrValue.FloatVal v) return v.value();
        if (value instanceof IrValue.DoubleVal v) return v.value();
        if (value instanceof IrValue.DurationVal v) return v.nanos();
        throw new IllegalArgumentException();
    }

    private String text(IrValue value) {
        if (value instanceof IrValue.StringVal v) return v.value();
        if (value instanceof IrValue.NullVal) return "null";
        if (value instanceof IrValue.BooleanVal v) return Boolean.toString(v.value());
        return number(value).toString();
    }
}
