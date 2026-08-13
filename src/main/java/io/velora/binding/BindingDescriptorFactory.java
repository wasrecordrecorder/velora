package io.velora.binding;

import io.velora.api.function.FunctionContext;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.permission.ScriptPermission;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.task.VeloraTask;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.binding.annotation.VeloraFunction;
import io.velora.binding.annotation.VeloraNamespace;
import io.velora.binding.annotation.VeloraProperty;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BindingDescriptorFactory {

    private final TypeRegistry typeRegistry;

    public BindingDescriptorFactory(TypeRegistry typeRegistry) {
        this.typeRegistry = typeRegistry;
    }

    public List<FunctionDescriptor> createDescriptors(Object binding) {
        if (binding == null) throw new BindingValidationException("Binding cannot be null");
        List<FunctionDescriptor> descriptors = new ArrayList<>();
        Class<?> cls = binding.getClass();
        VeloraNamespace nsAnnotation = cls.getAnnotation(VeloraNamespace.class);
        if (nsAnnotation == null) throw new BindingValidationException("Class " + cls.getName() + " is not annotated with @VeloraNamespace");
        String namespace = nsAnnotation.value();
        for (Method method : cls.getDeclaredMethods()) {
            VeloraFunction function = method.getAnnotation(VeloraFunction.class);
            VeloraProperty property = method.getAnnotation(VeloraProperty.class);
            if (function != null && property != null) throw new BindingValidationException("Method " + method.getName() + " cannot be both @VeloraFunction and @VeloraProperty");
            if (function != null) descriptors.add(createFunctionDescriptor(namespace, binding, method, function));
            else if (property != null) descriptors.add(createPropertyDescriptor(namespace, binding, method, property));
        }
        return descriptors;
    }

    private FunctionDescriptor createFunctionDescriptor(String namespace, Object binding, Method method, VeloraFunction annotation) {
        ensureAccessible(method);
        Type genericReturn = method.getGenericReturnType();
        VeloraType returnType;
        if (VeloraTask.class.isAssignableFrom(method.getReturnType())) {
            if (!annotation.suspending()) throw new BindingValidationException("VeloraTask return requires suspending=true in " + method.getName());
            if (!(genericReturn instanceof ParameterizedType parameterized) || parameterized.getActualTypeArguments().length != 1) {
                throw new BindingValidationException("VeloraTask return type must declare its result type in " + method.getName());
            }
            returnType = javaTypeToVelora(parameterized.getActualTypeArguments()[0], method, true);
        } else {
            returnType = javaTypeToVelora(genericReturn, method, true);
        }
        String description = defaultText(annotation.description(), "Function " + annotation.name());
        String categoryId = defaultText(annotation.category(), namespace);
        FunctionDescriptor.Builder builder = FunctionDescriptor.builder()
                .namespace(namespace)
                .name(annotation.name())
                .description(description)
                .categoryId(categoryId)
                .returns(returnType)
                .thread(annotation.thread())
                .suspending(annotation.suspending())
                .cost(annotation.cost());
        int scriptArgIndex = 0;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() == FunctionContext.class) continue;
            VeloraType parameterType = javaTypeToVelora(parameter.getParameterizedType(), method, false);
            String parameterName = parameter.isNamePresent() ? parameter.getName() : "arg" + scriptArgIndex;
            builder.parameter(parameterName, parameterType);
            scriptArgIndex++;
        }
        if (!annotation.permission().isEmpty()) builder.permission(ScriptPermission.of(annotation.permission(), annotation.permission(), ""));
        builder.invoker(context -> invoke(binding, method, extractArguments(method, context)));
        return builder.build();
    }

    private FunctionDescriptor createPropertyDescriptor(String namespace, Object binding, Method method, VeloraProperty annotation) {
        ensureAccessible(method);
        int contextCount = 0;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() != FunctionContext.class) throw new BindingValidationException("Property " + method.getName() + " cannot declare script parameters");
            contextCount++;
        }
        if (contextCount > 1) throw new BindingValidationException("Property " + method.getName() + " can declare at most one FunctionContext");
        VeloraType returnType = javaTypeToVelora(method.getGenericReturnType(), method, true);
        if (returnType == VeloraTypes.UNIT) throw new BindingValidationException("Property " + method.getName() + " cannot return Unit");
        FunctionDescriptor.Builder builder = FunctionDescriptor.builder()
                .namespace(namespace)
                .name(annotation.name())
                .description(defaultText(annotation.description(), "Property " + annotation.name()))
                .categoryId(defaultText(annotation.category(), namespace))
                .returns(returnType)
                .cost(annotation.cost());
        if (!annotation.permission().isEmpty()) builder.permission(ScriptPermission.of(annotation.permission(), annotation.permission(), ""));
        builder.invoker(context -> invoke(binding, method, extractArguments(method, context)));
        return builder.build();
    }

    private Object invoke(Object binding, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(binding, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private Object[] extractArguments(Method method, FunctionContext context) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        int scriptArgIndex = 0;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (type == FunctionContext.class) args[i] = context;
            else {
                Object value = context.argument(scriptArgIndex++);
                if (type == Duration.class) args[i] = Duration.ofNanos(((Number) value).longValue());
                else if (type == UUID.class) args[i] = value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
                else args[i] = context.argument(scriptArgIndex - 1, type);
            }
        }
        return args;
    }

    private VeloraType javaTypeToVelora(Type type, Method method, boolean allowVoid) {
        if (type instanceof WildcardType) throw unsupported(type, method);
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] args = parameterized.getActualTypeArguments();
            if (raw == List.class && args.length == 1) return VeloraTypes.list(javaTypeToVelora(args[0], method, false));
            if (raw == Set.class && args.length == 1) return VeloraTypes.set(javaTypeToVelora(args[0], method, false));
            if (raw == Map.class && args.length == 2) {
                VeloraType key = javaTypeToVelora(args[0], method, false);
                if (!key.isHashable()) throw new BindingValidationException("Map key type " + key.name() + " is not hashable in " + method.getName());
                return VeloraTypes.map(key, javaTypeToVelora(args[1], method, false));
            }
            throw unsupported(type, method);
        }
        if (!(type instanceof Class<?> javaType)) throw unsupported(type, method);
        if (javaType == void.class || javaType == Void.class) {
            if (allowVoid) return VeloraTypes.UNIT;
            throw unsupported(type, method);
        }
        if (javaType == byte.class || javaType == Byte.class) return VeloraTypes.BYTE;
        if (javaType == short.class || javaType == Short.class || javaType == int.class || javaType == Integer.class) return VeloraTypes.INT;
        if (javaType == long.class || javaType == Long.class) return VeloraTypes.LONG;
        if (javaType == float.class || javaType == Float.class) return VeloraTypes.FLOAT;
        if (javaType == double.class || javaType == Double.class) return VeloraTypes.DOUBLE;
        if (javaType == boolean.class || javaType == Boolean.class) return VeloraTypes.BOOLEAN;
        if (javaType == char.class || javaType == Character.class) return VeloraTypes.CHAR;
        if (javaType == String.class) return VeloraTypes.STRING;
        if (javaType == Duration.class) return VeloraTypes.DURATION;
        if (javaType == UUID.class) return VeloraTypes.UUID;
        if (javaType == Object.class || javaType == Thread.class || javaType == java.nio.file.Path.class) throw unsupported(type, method);
        if (List.class.isAssignableFrom(javaType) || Set.class.isAssignableFrom(javaType) || Map.class.isAssignableFrom(javaType) || VeloraTask.class.isAssignableFrom(javaType)) {
            throw new BindingValidationException("Generic type arguments are required for " + javaType.getTypeName() + " in " + method.getName());
        }
        if (typeRegistry != null) {
            VeloraType match = null;
            for (VeloraType candidate : typeRegistry.all()) {
                if (candidate.javaClass() != javaType) continue;
                if (match != null && !match.nonNull().name().equals(candidate.nonNull().name())) {
                    throw new BindingValidationException("Java type " + javaType.getTypeName() + " maps to multiple Velora types; use a dedicated binding type in " + method.getName());
                }
                match = candidate;
            }
            if (match != null) return match.nonNull();
        }
        throw unsupported(type, method);
    }

    private BindingValidationException unsupported(Type type, Method method) {
        return new BindingValidationException("Unsupported Java type " + type.getTypeName() + " in " + method.getDeclaringClass().getName() + "." + method.getName());
    }

    private void ensureAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (RuntimeException e) {
            throw new BindingValidationException("Cannot access binding method " + method.getName());
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
