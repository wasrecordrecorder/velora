package io.velora.binding;

import io.velora.api.function.FunctionDescriptor;
import io.velora.api.function.FunctionInvoker;
import io.velora.api.function.ScriptThread;
import io.velora.api.permission.ScriptPermission;
import io.velora.api.type.VeloraType;
import io.velora.binding.annotation.VeloraFunction;
import io.velora.binding.annotation.VeloraNamespace;
import io.velora.binding.annotation.VeloraProperty;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class BindingDescriptorFactory {

    private final JavaTypeAdapterRegistry adapterRegistry;

    public BindingDescriptorFactory(JavaTypeAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    public List<FunctionDescriptor> createDescriptors(Object binding) {
        List<FunctionDescriptor> descriptors = new ArrayList<>();
        Class<?> cls = binding.getClass();

        VeloraNamespace nsAnnotation = cls.getAnnotation(VeloraNamespace.class);
        if (nsAnnotation == null) {
            throw new BindingValidationException("Class " + cls.getName() + " is not annotated with @VeloraNamespace");
        }
        String namespace = nsAnnotation.value();

        for (Method method : cls.getDeclaredMethods()) {
            method.setAccessible(true);

            VeloraFunction funcAnnotation = method.getAnnotation(VeloraFunction.class);
            VeloraProperty propAnnotation = method.getAnnotation(VeloraProperty.class);

            if (funcAnnotation != null) {
                descriptors.add(createFunctionDescriptor(namespace, binding, method, funcAnnotation));
            } else if (propAnnotation != null) {
                descriptors.add(createPropertyDescriptor(namespace, binding, method, propAnnotation));
            }
        }

        return descriptors;
    }

    private FunctionDescriptor createFunctionDescriptor(String namespace, Object binding, Method method, VeloraFunction annotation) {
        validateReturnType(method.getReturnType(), method);
        for (Class<?> paramType : method.getParameterTypes()) {
            validateParamType(paramType, method);
        }
        String description = annotation.description();
        if (description == null || description.isBlank()) {
            description = "Function " + annotation.name();
        }
        String categoryId = annotation.category();
        if (categoryId == null || categoryId.isBlank()) {
            categoryId = namespace;
        }
        FunctionDescriptor.Builder builder = FunctionDescriptor.builder()
                .namespace(namespace)
                .name(annotation.name())
                .description(description)
                .categoryId(categoryId)
                .returns(javaTypeToVelora(method.getReturnType()))
                .thread(annotation.thread())
                .suspending(annotation.suspending())
                .cost(annotation.cost());

        if (!annotation.permission().isEmpty()) {
            builder.permission(ScriptPermission.of(annotation.permission(), annotation.permission(), ""));
        }

        builder.invoker(context -> {
            try {
                Object[] args = extractArguments(method, context);
                return method.invoke(binding, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        });

        return builder.build();
    }

    private FunctionDescriptor createPropertyDescriptor(String namespace, Object binding, Method method, VeloraProperty annotation) {
        validateReturnType(method.getReturnType(), method);
        String description = annotation.description();
        if (description == null || description.isBlank()) {
            description = "Property " + annotation.name();
        }
        String categoryId = annotation.category();
        if (categoryId == null || categoryId.isBlank()) {
            categoryId = namespace;
        }
        FunctionDescriptor.Builder builder = FunctionDescriptor.builder()
                .namespace(namespace)
                .name(annotation.name())
                .description(description)
                .categoryId(categoryId)
                .returns(javaTypeToVelora(method.getReturnType()))
                .cost(annotation.cost());

        if (!annotation.permission().isEmpty()) {
            builder.permission(ScriptPermission.of(annotation.permission(), annotation.permission(), ""));
        }

        builder.invoker(context -> {
            try {
                return method.invoke(binding);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        });

        return builder.build();
    }

    private Object[] extractArguments(Method method, io.velora.api.function.FunctionContext context) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }

        Object[] args = new Object[paramTypes.length];
        int argIndex = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == io.velora.api.function.FunctionContext.class) {
                args[i] = context;
            } else {
                args[i] = context.argument(argIndex, paramTypes[i]);
                argIndex++;
            }
        }
        return args;
    }

    private void validateReturnType(Class<?> type, Method method) {
        if (type == Object.class) {
            throw new BindingValidationException("Unsupported return type Object in " + method.getName());
        }
        if (type == java.nio.file.Path.class) {
            throw new BindingValidationException("Unsupported return type Path in " + method.getName());
        }
        if (type == Thread.class) {
            throw new BindingValidationException("Unsupported return type Thread in " + method.getName());
        }
    }

    private void validateParamType(Class<?> type, Method method) {
        if (type == Thread.class) {
            throw new BindingValidationException("Unsupported parameter type Thread in " + method.getName());
        }
        if (type == java.nio.file.Path.class) {
            throw new BindingValidationException("Unsupported parameter type Path in " + method.getName());
        }
        if (type == Object.class) {
            throw new BindingValidationException("Unsupported parameter type Object in " + method.getName());
        }
    }

    private VeloraType javaTypeToVelora(Class<?> javaType) {
        if (javaType == int.class || javaType == Integer.class) return io.velora.api.type.VeloraTypes.INT;
        if (javaType == long.class || javaType == Long.class) return io.velora.api.type.VeloraTypes.LONG;
        if (javaType == double.class || javaType == Double.class) return io.velora.api.type.VeloraTypes.DOUBLE;
        if (javaType == float.class || javaType == Float.class) return io.velora.api.type.VeloraTypes.FLOAT;
        if (javaType == boolean.class || javaType == Boolean.class) return io.velora.api.type.VeloraTypes.BOOLEAN;
        if (javaType == String.class) return io.velora.api.type.VeloraTypes.STRING;
        if (javaType == void.class || javaType == Void.class) return io.velora.api.type.VeloraTypes.UNIT;
        return io.velora.api.type.VeloraTypes.UNIT;
    }
}
