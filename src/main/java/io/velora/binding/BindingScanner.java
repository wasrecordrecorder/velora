package io.velora.binding;

import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;

import java.util.List;

public final class BindingScanner {

    private final BindingDescriptorFactory factory;

    public BindingScanner(BindingDescriptorFactory factory) {
        this.factory = factory;
    }

    public void scanAndRegister(Object binding, ApiRegistry registry) {
        List<FunctionDescriptor> descriptors = factory.createDescriptors(binding);
        for (FunctionDescriptor descriptor : descriptors) {
            registry.register(descriptor);
        }
    }

    public List<FunctionDescriptor> scan(Object binding) {
        return factory.createDescriptors(binding);
    }
}
