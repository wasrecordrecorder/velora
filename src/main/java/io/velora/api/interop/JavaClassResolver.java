package io.velora.api.interop;

@FunctionalInterface
public interface JavaClassResolver {
    String runtimeClassName(String sourceClassName);

    static JavaClassResolver identity() {
        return name -> name;
    }
}
