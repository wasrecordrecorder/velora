package io.velora.internal.interop;

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
import java.net.MalformedURLException;
import java.net.URLClassLoader;
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

public final class DefaultJavaImportRegistry implements JavaImportRegistry {
    private final DefaultApiRegistry apiRegistry;
    private final BindingDescriptorFactory factory;
    private final Map<String, JavaImportDescriptor> imports = new LinkedHashMap<>();
    private final Set<Class<?>> registeredClasses = new HashSet<>();
    private final Map<String, Class<?>> importClasses = new LinkedHashMap<>();
    private final List<URLClassLoader> pathLoaders = new ArrayList<>();
    private final List<Integer> loaderSnapshots = new ArrayList<>();
    private boolean frozen;

    public DefaultJavaImportRegistry(DefaultApiRegistry apiRegistry, TypeRegistry typeRegistry) {
        this.apiRegistry = apiRegistry;
        this.factory = new BindingDescriptorFactory(typeRegistry);
    }

    @Override
    public void register(Class<?> type) {
        if (type == null) throw new IllegalArgumentException("Java import class cannot be null");
        register(type, null);
    }

    @Override
    public void register(Path path) {
        register(path, defaultClassLoader());
    }

    @Override
    public void register(Path path, ClassLoader classLoader) {
        checkFrozen();
        if (path == null) throw new IllegalArgumentException("Java import path cannot be null");
        if (classLoader == null) throw new IllegalArgumentException("ClassLoader cannot be null");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) throw new IllegalArgumentException("Java import path does not exist: " + normalized);
        int importSnapshot = imports.size();
        int apiSnapshot = apiRegistry.all().size();
        Set<Class<?>> classSnapshot = Set.copyOf(registeredClasses);
        int loaderSnapshot = pathLoaders.size();
        try {
            if (Files.isDirectory(normalized)) registerDirectory(normalized, classLoader);
            else registerFile(normalized, classLoader);
        } catch (IOException | RuntimeException | LinkageError error) {
            apiRegistry.rollbackTo(apiSnapshot);
            rollbackTo(importSnapshot);
            registeredClasses.retainAll(classSnapshot);
            closeLoaders(loaderSnapshot);
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof LinkageError linkage) throw linkage;
            throw new BindingValidationException("Cannot scan Java import path " + normalized + ": " + error.getMessage());
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
        for (int i = pathLoaders.size() - 1; i >= 0 && loaderSnapshots.get(i) >= size; i--) closeLoader(i);
    }

    private void registerDirectory(Path root, ClassLoader classLoader) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(this::supportedFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        URLClassLoader directoryLoader = null;
        for (Path file : files) {
            String lower = file.getFileName().toString().toLowerCase();
            if (lower.endsWith(".class")) {
                ClassFileInfo info = readClassInfo(file);
                if (info.veloraImport()) {
                    if (directoryLoader == null) directoryLoader = pathLoader(root, classLoader);
                    registerRuntimeClass(info.name(), file, directoryLoader);
                }
            } else if (lower.endsWith(".jar")) registerJar(file, classLoader);
        }
    }

    private void registerFile(Path file, ClassLoader classLoader) throws IOException {
        String lower = file.getFileName().toString().toLowerCase();
        if (lower.endsWith(".class")) {
            ClassFileInfo info = readClassInfo(file);
            if (info.veloraImport()) registerRuntimeClass(info.name(), file, pathLoader(classRoot(file, info.name()), classLoader));
        } else if (lower.endsWith(".jar")) {
            registerJar(file, classLoader);
        } else {
            throw new IllegalArgumentException("Java imports accept Class<?>, .class, .jar or a compiled classes directory: " + file);
        }
    }

    private void registerJar(Path jar, ClassLoader parent) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/") && !entry.getName().equals("module-info.class") && !entry.getName().endsWith("package-info.class"))
                    .sorted(Comparator.comparing(entry -> entry.getName()))
                    .toList();
            URLClassLoader loader = null;
            for (var entry : entries) {
                ClassFileInfo info;
                try (InputStream stream = file.getInputStream(entry)) {
                    info = readClassInfo(stream, jar + "!/" + entry.getName());
                }
                if (info.veloraImport()) {
                    if (loader == null) loader = pathLoader(jar, parent);
                    registerRuntimeClass(info.name(), jar, loader);
                }
            }
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
        VeloraImport annotation = type.getAnnotation(VeloraImport.class);
        if (annotation == null) throw new BindingValidationException("Java import class " + type.getName() + " is not annotated with @VeloraImport");
        if (!registeredClasses.add(type)) return;
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
            imports.put(importName, new JavaImportDescriptor(importName, alias, namespace, type, source, annotation.description().trim()));
            importClasses.put(importName, type);
        } catch (RuntimeException | LinkageError error) {
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
            if (part.isEmpty() || !(Character.isLetter(part.charAt(0)) || part.charAt(0) == '_')) throw new BindingValidationException("Invalid Java import name: " + name);
            for (int i = 1; i < part.length(); i++) if (!Character.isLetterOrDigit(part.charAt(i)) && part.charAt(i) != '_') throw new BindingValidationException("Invalid Java import name: " + name);
        }
    }

    private URLClassLoader pathLoader(Path path, ClassLoader parent) throws MalformedURLException {
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, parent);
        pathLoaders.add(loader);
        loaderSnapshots.add(imports.size());
        return loader;
    }

    private Path classRoot(Path file, String className) {
        Path root = file.getParent();
        int dot = className.lastIndexOf('.');
        if (dot < 0) return root;
        for (String ignored : className.substring(0, dot).split("\\.")) {
            if (root == null) throw new BindingValidationException("Cannot resolve classpath root for " + file);
            root = root.getParent();
        }
        if (root == null) throw new BindingValidationException("Cannot resolve classpath root for " + file);
        return root;
    }

    private void closeLoaders(int fromIndex) {
        while (pathLoaders.size() > fromIndex) closeLoader(pathLoaders.size() - 1);
    }

    private void closeLoader(int index) {
        URLClassLoader loader = pathLoaders.remove(index);
        loaderSnapshots.remove(index);
        try { loader.close(); } catch (IOException ignored) { }
    }

    public void close() {
        closeLoaders(0);
    }

    private ClassFileInfo readClassInfo(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return readClassInfo(stream, file.toString());
        }
    }

    private ClassFileInfo readClassInfo(InputStream stream, String source) throws IOException {
        try (DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != 0xCAFEBABE) throw new BindingValidationException("Invalid class file: " + source);
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            if (count == 0) throw new BindingValidationException("Invalid constant pool in " + source);
            Object[] pool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3, 4 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        if (i + 1 >= count) throw new BindingValidationException("Invalid wide constant in " + source);
                        in.skipNBytes(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new BindingValidationException("Unsupported class constant pool tag " + tag + " in " + source);
                }
            }
            in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            in.readUnsignedShort();
            if (thisClass <= 0 || thisClass >= pool.length || !(pool[thisClass] instanceof Integer nameIndex)
                    || nameIndex <= 0 || nameIndex >= pool.length || !(pool[nameIndex] instanceof String name)) {
                throw new BindingValidationException("Invalid class name in " + source);
            }
            int interfaces = in.readUnsignedShort();
            in.skipNBytes((long) interfaces * 2);
            skipMembers(in);
            skipMembers(in);
            boolean exported = false;
            int attributes = in.readUnsignedShort();
            String descriptor = "L" + VeloraImport.class.getName().replace('.', '/') + ";";
            for (int i = 0; i < attributes; i++) {
                int attributeNameIndex = in.readUnsignedShort();
                long length = Integer.toUnsignedLong(in.readInt());
                String attributeName = attributeNameIndex > 0 && attributeNameIndex < pool.length && pool[attributeNameIndex] instanceof String value ? value : "";
                if (length > 64L * 1024 * 1024) throw new BindingValidationException("Class attribute is too large in " + source);
                if (attributeName.equals("RuntimeVisibleAnnotations")) {
                    byte[] data = in.readNBytes((int) length);
                    if (data.length != length) throw new IOException("Truncated class attribute in " + source);
                    try (DataInputStream annotations = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
                        exported |= hasAnnotation(annotations, pool, descriptor, 0);
                    }
                } else {
                    in.skipNBytes(length);
                }
            }
            return new ClassFileInfo(name.replace('/', '.'), exported);
        }
    }

    private void skipMembers(DataInputStream in) throws IOException {
        int members = in.readUnsignedShort();
        for (int i = 0; i < members; i++) {
            in.skipNBytes(6);
            int attributes = in.readUnsignedShort();
            for (int j = 0; j < attributes; j++) {
                in.skipNBytes(2);
                in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
            }
        }
    }

    private boolean hasAnnotation(DataInputStream in, Object[] pool, String target, int depth) throws IOException {
        if (depth > 64) throw new BindingValidationException("Annotation nesting is too deep");
        int annotations = in.readUnsignedShort();
        for (int i = 0; i < annotations; i++) {
            int typeIndex = in.readUnsignedShort();
            boolean match = typeIndex > 0 && typeIndex < pool.length && target.equals(pool[typeIndex]);
            int pairs = in.readUnsignedShort();
            for (int j = 0; j < pairs; j++) {
                in.readUnsignedShort();
                skipElementValue(in, pool, target, depth + 1);
            }
            if (match) return true;
        }
        return false;
    }

    private void skipElementValue(DataInputStream in, Object[] pool, String target, int depth) throws IOException {
        if (depth > 64) throw new BindingValidationException("Annotation nesting is too deep");
        switch (in.readUnsignedByte()) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.readUnsignedShort();
            case 'e' -> { in.readUnsignedShort(); in.readUnsignedShort(); }
            case '@' -> {
                in.readUnsignedShort();
                int pairs = in.readUnsignedShort();
                for (int i = 0; i < pairs; i++) {
                    in.readUnsignedShort();
                    skipElementValue(in, pool, target, depth + 1);
                }
            }
            case '[' -> {
                int values = in.readUnsignedShort();
                for (int i = 0; i < values; i++) skipElementValue(in, pool, target, depth + 1);
            }
            default -> throw new BindingValidationException("Invalid annotation element value");
        }
    }

    private record ClassFileInfo(String name, boolean veloraImport) {}

    private boolean supportedFile(Path file) {
        String lower = file.getFileName().toString().toLowerCase();
        return lower.endsWith(".class") || lower.endsWith(".jar");
    }

    private ClassLoader defaultClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DefaultJavaImportRegistry.class.getClassLoader();
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("JavaImportRegistry is frozen");
    }
}
