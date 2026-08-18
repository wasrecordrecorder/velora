package io.velora.binding;

import io.velora.api.function.FunctionContext;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.function.ScriptThread;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.task.VeloraTask;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.binding.annotation.VeloraFunction;
import io.velora.binding.annotation.VeloraNamespace;
import io.velora.binding.annotation.VeloraProperty;
import io.velora.binding.annotation.VeloraParam;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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
        Class<?> type = binding.getClass();
        VeloraNamespace namespace = type.getAnnotation(VeloraNamespace.class);
        if (namespace == null) throw new BindingValidationException("Class " + type.getName() + " is not annotated with @VeloraNamespace");
        return createDescriptors(type, binding, namespace.value(), false);
    }

    public List<FunctionDescriptor> createStaticDescriptors(Class<?> type, String namespace) {
        if (type == null) throw new BindingValidationException("Binding class cannot be null");
        return createDescriptors(type, null, namespace, true);
    }

    private List<FunctionDescriptor> createDescriptors(Class<?> type, Object binding, String namespace, boolean staticOnly) {
        List<FunctionDescriptor> descriptors = new ArrayList<>();
        Method[] methods = type.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(this::exposedName).thenComparing(Method::toGenericString));
        Set<String> names = new HashSet<>();
        for (Method method : methods) {
            VeloraFunction function = method.getAnnotation(VeloraFunction.class);
            VeloraProperty property = method.getAnnotation(VeloraProperty.class);
            if (function != null && property != null) throw new BindingValidationException("Method " + method.getName() + " cannot be both @VeloraFunction and @VeloraProperty");
            if (function == null && property == null) continue;
            if (staticOnly && !Modifier.isStatic(method.getModifiers())) throw new BindingValidationException("Imported method " + type.getName() + "." + method.getName() + " must be static");
            String name = function != null ? function.name() : property.name();
            if (!names.add(name)) throw new BindingValidationException("Duplicate exposed binding name " + namespace + "." + name);
            if (function != null) descriptors.add(createFunctionDescriptor(namespace, binding, method, function));
            else descriptors.add(createPropertyDescriptor(namespace, binding, method, property));
        }
        return descriptors;
    }

    private FunctionDescriptor createFunctionDescriptor(String namespace, Object binding, Method method, VeloraFunction annotation) {
        ensureAccessible(method);
        if (annotation.thread() == ScriptThread.WORKER && !annotation.suspending()) throw new BindingValidationException("WORKER function " + method.getName() + " must declare suspending=true");
        Type genericReturn = method.getGenericReturnType();
        VeloraType returnType;
        if (VeloraTask.class.isAssignableFrom(method.getReturnType())) {
            if (!annotation.suspending()) throw new BindingValidationException("VeloraTask return requires suspending=true in " + method.getName());
            if (!(genericReturn instanceof ParameterizedType parameterized) || parameterized.getActualTypeArguments().length != 1) {
                throw new BindingValidationException("VeloraTask return type must declare its result type in " + method.getName());
            }
            returnType = resolveAnnotatedType(annotation.returnType(), parameterized.getActualTypeArguments()[0], method, true);
        } else {
            returnType = resolveAnnotatedType(annotation.returnType(), genericReturn, method, true);
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
        int contextCount = 0;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() == FunctionContext.class) {
                if (++contextCount > 1) throw new BindingValidationException("Function " + method.getName() + " can declare at most one FunctionContext");
                continue;
            }
            VeloraParam named = parameter.getAnnotation(VeloraParam.class);
            VeloraType parameterType = resolveAnnotatedType(named != null ? named.type() : "", parameter.getParameterizedType(), method, false);
            String parameterName = named != null ? named.value() : "arg" + scriptArgIndex;
            if (!isIdentifier(parameterName)) throw new BindingValidationException("Invalid script parameter name '" + parameterName + "' in " + method.getName());
            builder.parameter(parameterName, parameterType);
            scriptArgIndex++;
        }
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
        VeloraType returnType = resolveAnnotatedType(annotation.returnType(), method.getGenericReturnType(), method, true);
        if (returnType == VeloraTypes.UNIT) throw new BindingValidationException("Property " + method.getName() + " cannot return Unit");
        if (annotation.thread() == ScriptThread.WORKER) throw new BindingValidationException("Property " + method.getName() + " cannot run on WORKER because properties are synchronous");
        FunctionDescriptor.Builder builder = FunctionDescriptor.builder()
                .namespace(namespace)
                .name(annotation.name())
                .description(defaultText(annotation.description(), "Property " + annotation.name()))
                .categoryId(defaultText(annotation.category(), namespace))
                .returns(returnType)
                .thread(annotation.thread())
                .cost(annotation.cost());
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


    private VeloraType resolveAnnotatedType(String override, Type javaType, Method method, boolean allowVoid) {
        if (override == null || override.isBlank()) return javaTypeToVelora(javaType, method, allowVoid);
        if (typeRegistry == null) throw new BindingValidationException("Type override '" + override + "' requires a TypeRegistry in " + method.getName());
        String name = override.trim();
        boolean nullable = name.endsWith("?");
        if (nullable) name = name.substring(0, name.length() - 1).trim();
        VeloraType type = typeRegistry.find(name);
        if (type == null) throw new BindingValidationException("Unknown Velora type '" + override + "' in " + method.getName());
        if (type == VeloraTypes.UNIT && !allowVoid) throw new BindingValidationException("Unit is not valid for a script parameter in " + method.getName());
        Class<?> actual = rawClass(javaType);
        if (actual == null || !sameJavaType(actual, type.javaClass())) {
            throw new BindingValidationException("Velora type " + type.name() + " uses Java type " + type.javaClass().getTypeName() + ", not " + javaType.getTypeName() + " in " + method.getName());
        }
        if (nullable && actual.isPrimitive()) throw new BindingValidationException("Nullable Velora type requires a nullable Java carrier in " + method.getName());
        return nullable ? type.nullable() : type.nonNull();
    }

    private Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) return cls;
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> cls) return cls;
        return null;
    }

    private boolean sameJavaType(Class<?> left, Class<?> right) {
        return boxed(left) == boxed(right);
    }

    private Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private VeloraType javaTypeToVelora(Type type, Method method, boolean allowVoid) {
        if (type instanceof WildcardType) throw unsupported(type, method);
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] args = parameterized.getActualTypeArguments();
            if (raw == List.class && args.length == 1) return VeloraTypes.list(javaTypeToVelora(args[0], method, false));
            if (raw == Set.class && args.length == 1) {
                VeloraType element = javaTypeToVelora(args[0], method, false);
                if (!element.isHashable()) throw new BindingValidationException("Set element type " + element.name() + " is not hashable in " + method.getName());
                return VeloraTypes.set(element);
            }
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

    private String exposedName(Method method) {
        VeloraFunction function = method.getAnnotation(VeloraFunction.class);
        if (function != null) return function.name();
        VeloraProperty property = method.getAnnotation(VeloraProperty.class);
        return property != null ? property.name() : "~" + method.getName();
    }

    private boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
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
