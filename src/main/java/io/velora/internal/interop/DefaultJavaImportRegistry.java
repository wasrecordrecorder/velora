package io.velora.internal.interop;

import io.velora.api.interop.JavaClassResolver;
import io.velora.api.interop.JavaImportDescriptor;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.binding.BindingDescriptorFactory;
import io.velora.binding.BindingValidationException;
import io.velora.binding.annotation.VeloraImport;
import io.velora.internal.registry.DefaultApiRegistry;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DefaultJavaImportRegistry implements JavaImportRegistry {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:(?:abstract|final|sealed|non-sealed|strictfp)\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern IMPORT_ANNOTATION = Pattern.compile("@(?:[A-Za-z_$][\\w$]*\\.)*VeloraImport\\s*\\(");

    private final DefaultApiRegistry apiRegistry;
    private final BindingDescriptorFactory factory;
    private final Map<String, JavaImportDescriptor> imports = new LinkedHashMap<>();
    private final Set<Class<?>> registeredClasses = new HashSet<>();
    private final Map<String, Class<?>> importClasses = new LinkedHashMap<>();
    private boolean frozen;

    public DefaultJavaImportRegistry(DefaultApiRegistry apiRegistry, TypeRegistry typeRegistry) {
        this.apiRegistry = apiRegistry;
        this.factory = new BindingDescriptorFactory(typeRegistry);
    }

    @Override
    public void register(Class<?> type) {
        register(type, null);
    }

    @Override
    public void register(Path path) {
        register(path, defaultClassLoader(), JavaClassResolver.identity());
    }

    @Override
    public void register(Path path, JavaClassResolver resolver) {
        register(path, defaultClassLoader(), resolver);
    }

    @Override
    public void register(Path path, ClassLoader classLoader, JavaClassResolver resolver) {
        checkFrozen();
        if (path == null) throw new IllegalArgumentException("Java import path cannot be null");
        if (classLoader == null) throw new IllegalArgumentException("ClassLoader cannot be null");
        if (resolver == null) throw new IllegalArgumentException("JavaClassResolver cannot be null");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) throw new IllegalArgumentException("Java import path does not exist: " + normalized);
        try {
            if (Files.isDirectory(normalized)) registerDirectory(normalized, classLoader, resolver);
            else registerFile(normalized, classLoader, resolver);
        } catch (IOException e) {
            throw new BindingValidationException("Cannot scan Java import path " + normalized + ": " + e.getMessage());
        }
    }

    @Override
    public JavaImportDescriptor find(String importName) {
        return imports.get(importName);
    }

    @Override
    public List<JavaImportDescriptor> all() {
        return List.copyOf(imports.values());
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    public void rollbackTo(int size) {
        while (imports.size() > size) {
            String key = imports.keySet().stream().reduce((first, second) -> second).orElse(null);
            if (key == null) break;
            imports.remove(key);
            Class<?> type = importClasses.remove(key);
            if (type != null) registeredClasses.remove(type);
        }
    }

    private void registerDirectory(Path root, ClassLoader classLoader, JavaClassResolver resolver) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(this::supportedFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path file : files) {
            String lower = file.getFileName().toString().toLowerCase();
            if (lower.endsWith(".java")) registerSource(file, classLoader, resolver);
            else if (lower.endsWith(".class")) registerRuntimeClass(binaryName(root, file), file, classLoader);
            else if (lower.endsWith(".jar")) registerJar(file, classLoader);
        }
    }

    private void registerFile(Path file, ClassLoader classLoader, JavaClassResolver resolver) throws IOException {
        String lower = file.getFileName().toString().toLowerCase();
        if (lower.endsWith(".java")) registerSource(file, classLoader, resolver);
        else if (lower.endsWith(".class")) registerRuntimeClass(readClassName(file), file, classLoader);
        else if (lower.endsWith(".jar")) registerJar(file, classLoader);
        else throw new IllegalArgumentException("Unsupported Java import file: " + file);
    }

    private void registerSource(Path source, ClassLoader classLoader, JavaClassResolver resolver) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        if (!IMPORT_ANNOTATION.matcher(text).find()) return;
        String sourceName = sourceClassName(text);
        if (sourceName == null) return;
        String runtimeName = resolver.runtimeClassName(sourceName);
        if (runtimeName == null || runtimeName.isBlank()) throw new BindingValidationException("JavaClassResolver returned an empty class name for " + sourceName);
        registerRuntimeClass(runtimeName, source, classLoader);
    }

    private void registerJar(Path jar, ClassLoader classLoader) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            List<String> classes = file.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class") && !name.equals("module-info.class") && !name.endsWith("package-info.class"))
                    .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                    .sorted()
                    .toList();
            for (String className : classes) registerRuntimeClass(className, jar, classLoader);
        }
    }

    private void registerRuntimeClass(String runtimeName, Path source, ClassLoader classLoader) {
        if (runtimeName == null || runtimeName.isBlank() || runtimeName.endsWith("module-info") || runtimeName.endsWith("package-info")) return;
        Class<?> type;
        try {
            type = Class.forName(runtimeName, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new BindingValidationException("Cannot load Java import class " + runtimeName + ": " + e.getMessage());
        }
        register(type, source);
    }

    private void register(Class<?> type, Path source) {
        checkFrozen();
        if (!registeredClasses.add(type)) return;
        VeloraImport annotation = type.getAnnotation(VeloraImport.class);
        if (annotation == null) return;
        int apiSnapshot = apiRegistry.all().size();
        try {
            String importName = annotation.value().trim();
            validateImportName(importName);
            String alias = importName.substring(importName.lastIndexOf('.') + 1);
            if (imports.containsKey(importName)) throw new BindingValidationException("Java import already registered: " + importName);
            String namespace = namespace(importName, alias);
            var descriptors = factory.createStaticDescriptors(type, namespace);
            if (descriptors.isEmpty()) throw new BindingValidationException("Java import " + importName + " has no @VeloraFunction or @VeloraProperty methods");
            for (var descriptor : descriptors) apiRegistry.register(descriptor);
            imports.put(importName, new JavaImportDescriptor(importName, alias, namespace, type.getName(), source));
            importClasses.put(importName, type);
        } catch (Throwable error) {
            apiRegistry.rollbackTo(apiSnapshot);
            registeredClasses.remove(type);
            throw error;
        }
    }

    private String namespace(String importName, String alias) {
        String base = "__java_" + alias + "_" + Integer.toUnsignedString(importName.hashCode(), 36);
        String namespace = base;
        int suffix = 2;
        while (apiRegistry.namespaces().contains(namespace)) namespace = base + "_" + suffix++;
        return namespace;
    }

    private void validateImportName(String name) {
        if (name.isEmpty()) throw new BindingValidationException("@VeloraImport value cannot be empty");
        String[] parts = name.split("\\.");
        for (String part : parts) {
            if (part.isEmpty() || !(Character.isJavaIdentifierStart(part.charAt(0)))) throw new BindingValidationException("Invalid Java import name: " + name);
            for (int i = 1; i < part.length(); i++) if (!Character.isJavaIdentifierPart(part.charAt(i))) throw new BindingValidationException("Invalid Java import name: " + name);
        }
    }

    private String sourceClassName(String text) {
        Matcher type = TYPE.matcher(text);
        if (!type.find()) return null;
        Matcher pkg = PACKAGE.matcher(text);
        return pkg.find() ? pkg.group(1) + "." + type.group(1) : type.group(1);
    }

    private String binaryName(Path root, Path file) {
        String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), ".");
        return relative.substring(0, relative.length() - 6);
    }

    private String readClassName(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file); DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != 0xCAFEBABE) throw new BindingValidationException("Invalid class file: " + file);
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Object[] pool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 15 -> in.skipBytes(3);
                    default -> throw new BindingValidationException("Unsupported class constant pool tag " + tag + " in " + file);
                }
            }
            in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            if (!(pool[thisClass] instanceof Integer nameIndex) || !(pool[nameIndex] instanceof String name)) throw new BindingValidationException("Invalid class name in " + file);
            return name.replace('/', '.');
        }
    }

    private boolean supportedFile(Path file) {
        String lower = file.getFileName().toString().toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".class") || lower.endsWith(".jar");
    }

    private ClassLoader defaultClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DefaultJavaImportRegistry.class.getClassLoader();
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("JavaImportRegistry is frozen");
    }
}
