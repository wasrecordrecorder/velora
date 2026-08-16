package io.velora.api.interop;

import java.nio.file.Path;
import java.util.List;

public interface JavaImportRegistry {
    void register(Class<?> type);

    void register(Path path);

    void register(Path path, JavaClassResolver resolver);

    void register(Path path, ClassLoader classLoader, JavaClassResolver resolver);

    JavaImportDescriptor find(String importName);

    List<JavaImportDescriptor> all();

    boolean isFrozen();
}
