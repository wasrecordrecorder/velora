package io.velora.api.gpu;

import io.velora.api.VeloraEngine;
import io.velora.api.category.ApiCategory;
import io.velora.api.data.ByteArray;
import io.velora.api.function.ScriptThread;
import io.velora.api.memory.MemoryFootprint;
import io.velora.api.script.ScriptServiceEvents;
import io.velora.api.type.EnumType;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GpuApi {
    private static final String CATEGORY = "gpu";

    private GpuApi() {
    }

    public static void register(VeloraEngine engine, GpuBackend backend) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(backend, "backend");
        if (engine.state() != io.velora.api.VeloraState.CONFIGURING) throw new IllegalStateException("GPU API must be registered before engine.freeze()");
        if (engine.api().find("gpu", "format") != null) throw new IllegalStateException("GPU API is already registered");
        if (engine.categories().byId(CATEGORY).isEmpty()) engine.categories().register(new ApiCategory(CATEGORY, "GPU", "Custom vertex, shader and pipeline rendering"));

        VeloraType attributeKind = enumType(engine, "GpuAttributeKind", GpuAttributeKind.class);
        VeloraType drawMode = enumType(engine, "GpuDrawMode", GpuDrawMode.class);
        VeloraType blend = enumType(engine, "GpuBlend", GpuBlend.class);
        VeloraType depth = enumType(engine, "GpuDepth", GpuDepth.class);
        VeloraType cull = enumType(engine, "GpuCull", GpuCull.class);
        VeloraType filter = enumType(engine, "GpuFilter", GpuFilter.class);
        VeloraType wrap = enumType(engine, "GpuWrap", GpuWrap.class);
        VeloraType space = enumType(engine, "GpuSpace", GpuSpace.class);
        VeloraType attribute = engine.types().struct("GpuAttribute", GpuAttribute.class, type -> type
                .property("name", VeloraTypes.STRING, value -> ((GpuAttribute) value).name())
                .property("kind", attributeKind, value -> ((GpuAttribute) value).kind())
                .property("components", VeloraTypes.INT, value -> ((GpuAttribute) value).components())
                .property("perInstance", VeloraTypes.BOOLEAN, value -> ((GpuAttribute) value).perInstance())
                .valueEquality(true));
        VeloraType format = engine.types().struct("GpuFormat", GpuFormat.class, type -> type
                .property("attributes", VeloraTypes.list(attribute), value -> ((GpuFormat) value).attributes())
                .property("vertexStride", VeloraTypes.INT, value -> ((GpuFormat) value).vertexStride())
                .property("instanceStride", VeloraTypes.INT, value -> ((GpuFormat) value).instanceStride())
                .valueEquality(true));
        VeloraType mesh = engine.types().handle("GpuMesh", GpuMesh.class);
        VeloraType shader = engine.types().handle("GpuShader", GpuShader.class);
        VeloraType texture = engine.types().handle("GpuTexture", GpuTexture.class);
        VeloraType matrix = engine.types().handle("GpuMat4", GpuMat4.class);
        VeloraType pipeline = engine.types().handle("GpuPipeline", GpuPipeline.class);
        VeloraType renderType = engine.types().handle("GpuRenderType", GpuRenderType.class);
        int maxBufferBytes = (int) Math.min(Integer.MAX_VALUE, Math.max(1_048_576L, Math.min(64L * 1024L * 1024L, engine.limits().memoryPerScript() / 2L)));
        int maxShaderLength = engine.limits().maxStringLength();
        int maxTextureBytes = (int) Math.min(16L * 1024L * 1024L, Math.max(1L, engine.limits().memoryPerScript() / 4L));
        GpuResources resources = new GpuResources(Math.max(1L, engine.limits().memoryPerScript() / 2L));

        engine.api().namespace("gpu", ns -> {
            ns.function("attribute", attribute, p -> p.required("name", VeloraTypes.STRING).required("kind", attributeKind).required("components", VeloraTypes.INT).optional("perInstance", VeloraTypes.BOOLEAN, false), ctx -> new GpuAttribute(ctx.argument(0, String.class), ctx.argument(1, GpuAttributeKind.class), ctx.argument(2, Integer.class), ctx.argument(3, Boolean.class))).description("Creates a vertex attribute").categoryId(CATEGORY);
            ns.function("floatAttribute", attribute, p -> p.required("name", VeloraTypes.STRING).required("components", VeloraTypes.INT).optional("perInstance", VeloraTypes.BOOLEAN, false), ctx -> new GpuAttribute(ctx.argument(0, String.class), GpuAttributeKind.FLOAT, ctx.argument(1, Integer.class), ctx.argument(2, Boolean.class))).description("Creates a floating-point vertex attribute").categoryId(CATEGORY);
            ns.function("intAttribute", attribute, p -> p.required("name", VeloraTypes.STRING).required("components", VeloraTypes.INT).optional("perInstance", VeloraTypes.BOOLEAN, false), ctx -> new GpuAttribute(ctx.argument(0, String.class), GpuAttributeKind.INT, ctx.argument(1, Integer.class), ctx.argument(2, Boolean.class))).description("Creates an integer vertex attribute").categoryId(CATEGORY);
            ns.function("colorAttribute", attribute, p -> p.required("name", VeloraTypes.STRING).optional("perInstance", VeloraTypes.BOOLEAN, false), ctx -> new GpuAttribute(ctx.argument(0, String.class), GpuAttributeKind.COLOR, 4, ctx.argument(1, Boolean.class))).description("Creates a normalized RGBA color attribute").categoryId(CATEGORY);
            ns.function("format", format, p -> p.required("attributes", VeloraTypes.list(attribute)), ctx -> new GpuFormat(castList(ctx.argument(0, List.class), GpuAttribute.class))).description("Creates a validated vertex format").categoryId(CATEGORY);
            ns.function("mesh", mesh, p -> p.required("format", format), ctx -> resources.create(ctx.scriptId(), ctx.argument(0, GpuFormat.class), false, maxBufferBytes)).description("Creates a non-indexed mesh").categoryId(CATEGORY);
            ns.function("indexedMesh", mesh, p -> p.required("format", format), ctx -> resources.create(ctx.scriptId(), ctx.argument(0, GpuFormat.class), true, maxBufferBytes)).description("Creates an indexed mesh").categoryId(CATEGORY);
            ns.function("vertices", mesh, p -> p.required("mesh", mesh), ctx -> ctx.argument(0, GpuMesh.class).stream(false)).description("Selects the per-vertex stream").categoryId(CATEGORY);
            ns.function("instances", mesh, p -> p.required("mesh", mesh), ctx -> ctx.argument(0, GpuMesh.class).stream(true)).description("Selects the per-instance stream").categoryId(CATEGORY);
            ns.function("putFloat", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuMesh.class).putFloat(ctx.argument(1, Float.class))).description("Writes one floating-point attribute").categoryId(CATEGORY);
            ns.function("putVec2", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.FLOAT).required("y", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuMesh.class).putFloats(ctx.argument(1, Float.class), ctx.argument(2, Float.class))).description("Writes a two-component floating-point attribute").categoryId(CATEGORY);
            ns.function("putVec3", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.FLOAT).required("y", VeloraTypes.FLOAT).required("z", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuMesh.class).putFloats(ctx.argument(1, Float.class), ctx.argument(2, Float.class), ctx.argument(3, Float.class))).description("Writes a three-component floating-point attribute").categoryId(CATEGORY);
            ns.function("putVec4", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.FLOAT).required("y", VeloraTypes.FLOAT).required("z", VeloraTypes.FLOAT).required("w", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuMesh.class).putFloats(ctx.argument(1, Float.class), ctx.argument(2, Float.class), ctx.argument(3, Float.class), ctx.argument(4, Float.class))).description("Writes a four-component floating-point attribute").categoryId(CATEGORY);
            ns.function("putUv", mesh, p -> p.required("mesh", mesh).required("u", VeloraTypes.FLOAT).required("v", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuMesh.class).putFloats(ctx.argument(1, Float.class), ctx.argument(2, Float.class))).description("Writes a two-component texture coordinate attribute").categoryId(CATEGORY);
            ns.function("putInt", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putInts(ctx.argument(1, Integer.class))).description("Writes one integer attribute").categoryId(CATEGORY);
            ns.function("putIVec2", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.INT).required("y", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putInts(ctx.argument(1, Integer.class), ctx.argument(2, Integer.class))).description("Writes a two-component integer attribute").categoryId(CATEGORY);
            ns.function("putIVec3", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.INT).required("y", VeloraTypes.INT).required("z", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putInts(ctx.argument(1, Integer.class), ctx.argument(2, Integer.class), ctx.argument(3, Integer.class))).description("Writes a three-component integer attribute").categoryId(CATEGORY);
            ns.function("putIVec4", mesh, p -> p.required("mesh", mesh).required("x", VeloraTypes.INT).required("y", VeloraTypes.INT).required("z", VeloraTypes.INT).required("w", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putInts(ctx.argument(1, Integer.class), ctx.argument(2, Integer.class), ctx.argument(3, Integer.class), ctx.argument(4, Integer.class))).description("Writes a four-component integer attribute").categoryId(CATEGORY);
            ns.function("putColor", mesh, p -> p.required("mesh", mesh).required("argb", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putColor(ctx.argument(1, Integer.class))).description("Writes an ARGB color attribute as normalized RGBA bytes").categoryId(CATEGORY);
            ns.function("putIndex", mesh, p -> p.required("mesh", mesh).required("index", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putIndices(ctx.argument(1, Integer.class))).description("Writes one mesh index").categoryId(CATEGORY);
            ns.function("putTriangle", mesh, p -> p.required("mesh", mesh).required("a", VeloraTypes.INT).required("b", VeloraTypes.INT).required("c", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putIndices(ctx.argument(1, Integer.class), ctx.argument(2, Integer.class), ctx.argument(3, Integer.class))).description("Writes three triangle indices").categoryId(CATEGORY);
            ns.function("putQuad", mesh, p -> p.required("mesh", mesh).required("a", VeloraTypes.INT).required("b", VeloraTypes.INT).required("c", VeloraTypes.INT).required("d", VeloraTypes.INT), ctx -> ctx.argument(0, GpuMesh.class).putQuad(ctx.argument(1, Integer.class), ctx.argument(2, Integer.class), ctx.argument(3, Integer.class), ctx.argument(4, Integer.class))).description("Writes two indexed triangles for a quad").categoryId(CATEGORY);
            ns.function("clear", mesh, p -> p.required("mesh", mesh), ctx -> ctx.argument(0, GpuMesh.class).clear()).description("Clears vertex, instance and index data").categoryId(CATEGORY);
            ns.function("releaseMesh", VeloraTypes.UNIT, p -> p.required("mesh", mesh), ctx -> {
                resources.release(ctx.scriptId(), ctx.argument(0, GpuMesh.class));
                return null;
            }).description("Releases a mesh and its retained CPU memory").categoryId(CATEGORY);
            ns.function("vertexCount", VeloraTypes.INT, p -> p.required("mesh", mesh), ctx -> ctx.argument(0, GpuMesh.class).vertexCount()).description("Returns the complete vertex count").categoryId(CATEGORY);
            ns.function("instanceCount", VeloraTypes.INT, p -> p.required("mesh", mesh), ctx -> ctx.argument(0, GpuMesh.class).instanceCount()).description("Returns the complete instance count").categoryId(CATEGORY);
            ns.function("shader", shader, p -> p.required("vertexSource", VeloraTypes.STRING).required("fragmentSource", VeloraTypes.STRING), ctx -> new GpuShader(ctx.argument(0, String.class), ctx.argument(1, String.class), maxShaderLength)).description("Creates a GLSL shader program definition").categoryId(CATEGORY);
            ns.function("texture", texture, p -> p.required("data", VeloraTypes.BYTE_ARRAY).optional("filter", filter.nullable(), null).optional("wrap", wrap.nullable(), null), ctx -> {
                ByteArray data = ctx.argument(0, ByteArray.class);
                if (data.size() > maxTextureBytes) throw new IllegalArgumentException("GPU texture exceeds the encoded image limit: " + maxTextureBytes);
                return new GpuTexture(data, Objects.requireNonNullElse(ctx.argument(1, GpuFilter.class), GpuFilter.LINEAR), Objects.requireNonNullElse(ctx.argument(2, GpuWrap.class), GpuWrap.CLAMP));
            }).description("Creates an encoded image texture with explicit sampling").categoryId(CATEGORY);
            ns.function("mat4", matrix, p -> p.variadic("values", VeloraTypes.FLOAT), ctx -> new GpuMat4(floatArguments(ctx))).description("Creates a 4x4 column-major matrix from sixteen values").categoryId(CATEGORY);
            ns.function("pipeline", pipeline, p -> p.required("shader", shader).required("drawMode", drawMode).required("blend", blend).required("depth", depth).optional("cull", cull.nullable(), null), ctx -> new GpuPipeline(ctx.argument(0, GpuShader.class), ctx.argument(1, GpuDrawMode.class), ctx.argument(2, GpuBlend.class), ctx.argument(3, GpuDepth.class), Objects.requireNonNullElse(ctx.argument(4, GpuCull.class), GpuCull.OFF))).description("Creates a graphics pipeline").categoryId(CATEGORY);
            ns.function("setMat4", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("value", matrix), ctx -> ctx.argument(0, GpuPipeline.class).mat4(ctx.argument(1, String.class), ctx.argument(2, GpuMat4.class))).description("Sets a mat4 shader uniform").categoryId(CATEGORY);
            ns.function("setFloat", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("value", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuPipeline.class).floats(ctx.argument(1, String.class), ctx.argument(2, Float.class))).description("Sets a float shader uniform").categoryId(CATEGORY);
            ns.function("setVec2", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("x", VeloraTypes.FLOAT).required("y", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuPipeline.class).floats(ctx.argument(1, String.class), ctx.argument(2, Float.class), ctx.argument(3, Float.class))).description("Sets a vec2 shader uniform").categoryId(CATEGORY);
            ns.function("setVec3", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("x", VeloraTypes.FLOAT).required("y", VeloraTypes.FLOAT).required("z", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuPipeline.class).floats(ctx.argument(1, String.class), ctx.argument(2, Float.class), ctx.argument(3, Float.class), ctx.argument(4, Float.class))).description("Sets a vec3 shader uniform").categoryId(CATEGORY);
            ns.function("setVec4", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("x", VeloraTypes.FLOAT).required("y", VeloraTypes.FLOAT).required("z", VeloraTypes.FLOAT).required("w", VeloraTypes.FLOAT), ctx -> ctx.argument(0, GpuPipeline.class).floats(ctx.argument(1, String.class), ctx.argument(2, Float.class), ctx.argument(3, Float.class), ctx.argument(4, Float.class), ctx.argument(5, Float.class))).description("Sets a vec4 shader uniform").categoryId(CATEGORY);
            ns.function("setInt", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("value", VeloraTypes.INT), ctx -> ctx.argument(0, GpuPipeline.class).integer(ctx.argument(1, String.class), ctx.argument(2, Integer.class))).description("Sets an integer shader uniform").categoryId(CATEGORY);
            ns.function("setTexture", pipeline, p -> p.required("pipeline", pipeline).required("name", VeloraTypes.STRING).required("texture", texture), ctx -> ctx.argument(0, GpuPipeline.class).texture(ctx.argument(1, String.class), ctx.argument(2, GpuTexture.class))).description("Binds a texture to a sampler uniform").categoryId(CATEGORY);
            ns.function("renderType", renderType, p -> p.required("pipeline", pipeline).optional("space", space.nullable(), null), ctx -> new GpuRenderType(ctx.argument(0, GpuPipeline.class), Objects.requireNonNullElse(ctx.argument(1, GpuSpace.class), GpuSpace.HUD), false)).description("Creates a normal render type").categoryId(CATEGORY);
            ns.function("instancedRenderType", renderType, p -> p.required("pipeline", pipeline).optional("space", space.nullable(), null), ctx -> new GpuRenderType(ctx.argument(0, GpuPipeline.class), Objects.requireNonNullElse(ctx.argument(1, GpuSpace.class), GpuSpace.HUD), true)).description("Creates an instanced render type").categoryId(CATEGORY);
            ns.function("draw", VeloraTypes.UNIT, p -> p.required("renderType", renderType).required("mesh", mesh), ctx -> {
                GpuMesh value = ctx.argument(1, GpuMesh.class);
                value.requireOwner(ctx.scriptId());
                backend.submit(ctx.scriptId(), ctx.argument(0, GpuRenderType.class), value);
                return null;
            }).description("Submits a validated draw call to the host backend").categoryId(CATEGORY).thread(ScriptThread.MAIN).cost(25);
            ns.function("lastError", VeloraTypes.STRING, ctx -> Objects.requireNonNullElse(backend.lastError(ctx.scriptId()), "")).description("Returns the last backend error for this script").categoryId(CATEGORY);
            ns.function("reset", VeloraTypes.UNIT, ctx -> {
                resources.reset(ctx.scriptId());
                backend.reset(ctx.scriptId());
                return null;
            }).description("Releases backend resources owned by this script").categoryId(CATEGORY).thread(ScriptThread.MAIN);
        });
        engine.scripts().events().subscribe(event -> {
            if (event.type() == ScriptServiceEvents.Type.DISABLED || event.type() == ScriptServiceEvents.Type.DELETED || event.type() == ScriptServiceEvents.Type.RUNTIME_ERROR) {
                resources.reset(event.scriptId());
                backend.reset(event.scriptId());
            }
        });
    }

    private static <E extends Enum<E>> VeloraType enumType(VeloraEngine engine, String name, Class<E> type) {
        List<EnumType.Constant> values = Arrays.stream(type.getEnumConstants()).map(value -> new EnumType.Constant(value.name(), value)).toList();
        return engine.types().enumType(name, type, values);
    }

    private static <T> List<T> castList(List<?> values, Class<T> type) {
        List<T> result = new ArrayList<>(values.size());
        for (Object value : values) result.add(type.cast(value));
        return result;
    }

    private static float[] floatArguments(io.velora.api.function.FunctionContext context) {
        if (context.argumentCount() != 16) throw new IllegalArgumentException("mat4 requires exactly sixteen values");
        float[] result = new float[16];
        for (int i = 0; i < result.length; i++) {
            Object value = context.argument(i);
            if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) throw new IllegalArgumentException("Matrix values must be finite numbers");
            result[i] = number.floatValue();
        }
        return result;
    }

    public enum GpuAttributeKind {
        FLOAT,
        INT,
        COLOR
    }

    public enum GpuDrawMode {
        TRIANGLES,
        TRIANGLE_STRIP,
        TRIANGLE_FAN,
        LINES,
        LINE_STRIP,
        POINTS
    }

    public enum GpuBlend {
        OFF,
        ALPHA,
        PREMULTIPLIED,
        ADDITIVE
    }

    public enum GpuDepth {
        OFF,
        TEST,
        TEST_AND_WRITE
    }

    public enum GpuCull {
        OFF,
        BACK,
        FRONT
    }

    public enum GpuFilter {
        NEAREST,
        LINEAR
    }

    public enum GpuWrap {
        CLAMP,
        REPEAT
    }

    public enum GpuSpace {
        HUD,
        WORLD
    }

    public record GpuAttribute(String name, GpuAttributeKind kind, int components, boolean perInstance) {
        public GpuAttribute {
            name = Objects.requireNonNull(name, "name").trim();
            kind = Objects.requireNonNull(kind, "kind");
            if (!identifier(name)) throw new IllegalArgumentException("Attribute name must be a GLSL identifier: " + name);
            if (kind == GpuAttributeKind.COLOR && components != 4) throw new IllegalArgumentException("Color attributes always have four components");
            if (kind != GpuAttributeKind.COLOR && (components < 1 || components > 4)) throw new IllegalArgumentException("Attribute components must be between 1 and 4");
        }

        public int bytes() {
            return kind == GpuAttributeKind.COLOR ? 4 : components * Integer.BYTES;
        }
    }

    public static final class GpuFormat {
        private final List<GpuAttribute> attributes;
        private final List<LayoutAttribute> layout;
        private final int vertexStride;
        private final int instanceStride;

        public GpuFormat(List<GpuAttribute> attributes) {
            Objects.requireNonNull(attributes, "attributes");
            if (attributes.isEmpty() || attributes.size() > 16) throw new IllegalArgumentException("A GPU format requires between 1 and 16 attributes");
            List<GpuAttribute> copy = List.copyOf(attributes);
            if (copy.stream().map(GpuAttribute::name).distinct().count() != copy.size()) throw new IllegalArgumentException("GPU attribute names must be unique");
            List<LayoutAttribute> built = new ArrayList<>(copy.size());
            int vertexOffset = 0;
            int instanceOffset = 0;
            for (int location = 0; location < copy.size(); location++) {
                GpuAttribute attribute = copy.get(location);
                int offset = attribute.perInstance() ? instanceOffset : vertexOffset;
                built.add(new LayoutAttribute(attribute, location, offset));
                if (attribute.perInstance()) instanceOffset = Math.addExact(instanceOffset, attribute.bytes());
                else vertexOffset = Math.addExact(vertexOffset, attribute.bytes());
            }
            if (vertexOffset == 0) throw new IllegalArgumentException("A GPU format requires at least one per-vertex attribute");
            this.attributes = copy;
            this.layout = List.copyOf(built);
            this.vertexStride = vertexOffset;
            this.instanceStride = instanceOffset;
        }

        public List<GpuAttribute> attributes() {
            return attributes;
        }

        public List<LayoutAttribute> layout() {
            return layout;
        }

        public int vertexStride() {
            return vertexStride;
        }

        public int instanceStride() {
            return instanceStride;
        }

        List<GpuAttribute> streamAttributes(boolean instances) {
            return attributes.stream().filter(attribute -> attribute.perInstance() == instances).toList();
        }

        @Override
        public boolean equals(Object value) {
            return this == value || value instanceof GpuFormat other && attributes.equals(other.attributes);
        }

        @Override
        public int hashCode() {
            return attributes.hashCode();
        }

        @Override
        public String toString() {
            return "GpuFormat" + attributes;
        }
    }

    public record LayoutAttribute(GpuAttribute attribute, int location, int offset) {
    }

    public static final class GpuMesh {
        private final String owner;
        private final ScriptGpuResources resources;
        private final GpuFormat format;
        private final boolean indexed;
        private final int maxBufferBytes;
        private final BinaryBuffer vertices = new BinaryBuffer();
        private final BinaryBuffer instances = new BinaryBuffer();
        private final IntBufferBuilder indices = new IntBufferBuilder();
        private boolean instanceStream;
        private int vertexAttribute;
        private int instanceAttribute;
        private GpuMeshData snapshot;
        private long reservedBytes;
        private long snapshotBytes;
        private boolean released;

        GpuMesh(String owner, ScriptGpuResources resources, GpuFormat format, boolean indexed, int maxBufferBytes) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.resources = Objects.requireNonNull(resources, "resources");
            this.format = Objects.requireNonNull(format, "format");
            this.indexed = indexed;
            this.maxBufferBytes = maxBufferBytes;
        }

        public synchronized GpuMesh stream(boolean instances) {
            requireActive();
            if (instances && format.instanceStride() == 0) throw new IllegalStateException("Format has no per-instance attributes");
            instanceStream = instances;
            return this;
        }

        public synchronized GpuMesh putFloat(float value) {
            return putFloats(value);
        }

        public synchronized GpuMesh putFloats(float... values) {
            requireActive();
            for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Vertex values must be finite");
            GpuAttribute expected = expected(GpuAttributeKind.FLOAT, values.length);
            write(values.length * Float.BYTES, () -> target().putFloats(values));
            advance(expected);
            return this;
        }

        public synchronized GpuMesh putInts(int... values) {
            requireActive();
            GpuAttribute expected = expected(GpuAttributeKind.INT, values.length);
            write(values.length * Integer.BYTES, () -> target().putInts(values));
            advance(expected);
            return this;
        }

        public synchronized GpuMesh putColor(int argb) {
            requireActive();
            GpuAttribute expected = expected(GpuAttributeKind.COLOR, 4);
            write(4, () -> target().putBytes((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255));
            advance(expected);
            return this;
        }

        public synchronized GpuMesh putIndices(int... values) {
            requireActive();
            if (!indexed) throw new IllegalStateException("Mesh is not indexed");
            for (int value : values) if (value < 0) throw new IllegalArgumentException("Mesh indices cannot be negative");
            long required = (long) indices.size() * Integer.BYTES + (long) values.length * Integer.BYTES;
            if (required > maxBufferBytes) throw new IllegalStateException("GPU index buffer exceeds the per-mesh limit");
            long bytes = (long) values.length * Integer.BYTES;
            invalidateSnapshot();
            resources.reserve(bytes);
            try {
                indices.addAll(values);
                reservedBytes += bytes;
            } catch (Throwable error) {
                resources.release(bytes);
                throw error;
            }
            return this;
        }

        public synchronized GpuMesh putQuad(int a, int b, int c, int d) {
            return putIndices(a, b, c, a, c, d);
        }

        public synchronized GpuMesh clear() {
            requireActive();
            clearData();
            return this;
        }

        public synchronized int dataBytes() {
            requireActive();
            return Math.toIntExact(reservedBytes);
        }

        public synchronized int vertexCount() {
            requireActive();
            return vertices.size() / format.vertexStride();
        }

        public synchronized int instanceCount() {
            requireActive();
            return format.instanceStride() == 0 ? 0 : instances.size() / format.instanceStride();
        }

        public synchronized GpuMeshData snapshot() {
            requireActive();
            if (snapshot != null) return snapshot;
            if (vertexAttribute != 0 || instanceAttribute != 0) throw new IllegalStateException("GPU mesh contains an incomplete attribute sequence");
            int vertexCount = vertexCount();
            if (vertexCount == 0) throw new IllegalStateException("GPU mesh has no vertices");
            int[] indexData = indices.toArray();
            if (indexed && indexData.length == 0) throw new IllegalStateException("Indexed GPU mesh has no indices");
            if (!indexed && indexData.length != 0) throw new IllegalStateException("Non-indexed GPU mesh cannot contain indices");
            for (int index : indexData) if (index >= vertexCount) throw new IllegalStateException("GPU mesh index " + index + " exceeds vertex count " + vertexCount);
            long bytes = reservedBytes;
            resources.reserve(bytes);
            try {
                snapshot = new GpuMeshData(format, indexed, vertices.toArray(), instances.toArray(), indexData, vertexCount, instanceCount());
                snapshotBytes = bytes;
                return snapshot;
            } catch (Throwable error) {
                resources.release(bytes);
                throw error;
            }
        }

        void requireOwner(String scriptId) {
            if (!owner.equals(scriptId)) throw new IllegalArgumentException("GPU mesh belongs to another script");
        }

        synchronized void release() {
            if (released) return;
            clearData();
            released = true;
        }

        private void clearData() {
            invalidateSnapshot();
            vertices.clear();
            instances.clear();
            indices.clear();
            resources.release(reservedBytes);
            reservedBytes = 0;
            instanceStream = false;
            vertexAttribute = 0;
            instanceAttribute = 0;
        }

        private GpuAttribute expected(GpuAttributeKind kind, int components) {
            List<GpuAttribute> attributes = format.streamAttributes(instanceStream);
            if (attributes.isEmpty()) throw new IllegalStateException(instanceStream ? "Format has no per-instance attributes" : "Format has no per-vertex attributes");
            int cursor = instanceStream ? instanceAttribute : vertexAttribute;
            GpuAttribute expected = attributes.get(cursor);
            if (expected.kind() != kind || expected.components() != components) throw new IllegalStateException("Expected " + expected.kind() + "[" + expected.components() + "] attribute '" + expected.name() + "'");
            return expected;
        }

        private void advance(GpuAttribute ignored) {
            int count = format.streamAttributes(instanceStream).size();
            if (instanceStream) instanceAttribute = (instanceAttribute + 1) % count;
            else vertexAttribute = (vertexAttribute + 1) % count;
        }

        private BinaryBuffer target() {
            return instanceStream ? instances : vertices;
        }

        private void write(int bytes, Runnable action) {
            if ((long) vertices.size() + instances.size() + bytes > maxBufferBytes) throw new IllegalStateException("GPU vertex buffers exceed the per-mesh limit");
            invalidateSnapshot();
            resources.reserve(bytes);
            try {
                action.run();
                reservedBytes += bytes;
            } catch (Throwable error) {
                resources.release(bytes);
                throw error;
            }
        }

        private void invalidateSnapshot() {
            if (snapshot == null) return;
            snapshot = null;
            resources.release(snapshotBytes);
            snapshotBytes = 0;
        }

        private void requireActive() {
            if (released) throw new IllegalStateException("GPU mesh was released");
        }

        @Override
        public synchronized String toString() {
            return released ? "GpuMesh(released)" : "GpuMesh(vertices=" + vertices.size() / format.vertexStride() + ", instances=" + (format.instanceStride() == 0 ? 0 : instances.size() / format.instanceStride()) + ", indexed=" + indexed + ")";
        }
    }

    public static final class GpuShader implements MemoryFootprint {
        private final String vertexSource;
        private final String fragmentSource;

        GpuShader(String vertexSource, String fragmentSource, int maxLength) {
            this.vertexSource = shaderSource(vertexSource, "vertex", maxLength);
            this.fragmentSource = shaderSource(fragmentSource, "fragment", maxLength);
        }

        public String vertexSource() {
            return vertexSource;
        }

        public String fragmentSource() {
            return fragmentSource;
        }

        @Override
        public long estimatedBytes() {
            return (long) (vertexSource.length() + fragmentSource.length()) * Character.BYTES;
        }
    }

    public static final class GpuTexture implements MemoryFootprint {
        private final ByteArray data;
        private final GpuFilter filter;
        private final GpuWrap wrap;

        public GpuTexture(ByteArray data, GpuFilter filter, GpuWrap wrap) {
            this.data = Objects.requireNonNull(data, "data");
            this.filter = Objects.requireNonNull(filter, "filter");
            this.wrap = Objects.requireNonNull(wrap, "wrap");
            if (data.isEmpty()) throw new IllegalArgumentException("Texture data cannot be empty");
        }

        public ByteArray data() {
            return data;
        }

        public GpuFilter filter() {
            return filter;
        }

        public GpuWrap wrap() {
            return wrap;
        }

        @Override
        public long estimatedBytes() {
            return data.size();
        }
    }

    public static final class GpuMat4 implements MemoryFootprint {
        private final float[] values;

        GpuMat4(float[] values) {
            if (values.length != 16) throw new IllegalArgumentException("mat4 requires sixteen values");
            this.values = values.clone();
        }

        public float[] values() {
            return values.clone();
        }

        @Override
        public long estimatedBytes() {
            return (long) values.length * Float.BYTES;
        }
    }

    public static final class GpuPipeline {
        private static final int MAX_UNIFORMS = 128;
        private static final int MAX_TEXTURES = 16;
        private final GpuShader shader;
        private final GpuDrawMode drawMode;
        private final GpuBlend blend;
        private final GpuDepth depth;
        private final GpuCull cull;
        private final Map<String, float[]> floatUniforms = new LinkedHashMap<>();
        private final Map<String, Integer> intUniforms = new LinkedHashMap<>();
        private final Map<String, GpuTexture> textures = new LinkedHashMap<>();

        GpuPipeline(GpuShader shader, GpuDrawMode drawMode, GpuBlend blend, GpuDepth depth, GpuCull cull) {
            this.shader = Objects.requireNonNull(shader, "shader");
            this.drawMode = Objects.requireNonNull(drawMode, "drawMode");
            this.blend = Objects.requireNonNull(blend, "blend");
            this.depth = Objects.requireNonNull(depth, "depth");
            this.cull = Objects.requireNonNull(cull, "cull");
        }

        public synchronized GpuPipeline mat4(String name, GpuMat4 value) {
            String key = prepareUniform(name, floatUniforms);
            floatUniforms.put(key, value.values());
            return this;
        }

        public synchronized GpuPipeline floats(String name, float... values) {
            if (values.length < 1 || values.length > 4) throw new IllegalArgumentException("Float uniforms require between one and four values");
            for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Uniform values must be finite");
            String key = prepareUniform(name, floatUniforms);
            floatUniforms.put(key, values.clone());
            return this;
        }

        public synchronized GpuPipeline integer(String name, int value) {
            String key = prepareUniform(name, intUniforms);
            intUniforms.put(key, value);
            return this;
        }

        public synchronized GpuPipeline texture(String name, GpuTexture value) {
            String key = uniformName(name);
            if (!textures.containsKey(key) && textures.size() >= MAX_TEXTURES) throw new IllegalStateException("A GPU pipeline supports at most " + MAX_TEXTURES + " textures");
            key = prepareUniform(key, textures);
            textures.put(key, Objects.requireNonNull(value, "value"));
            return this;
        }

        private String prepareUniform(String name, Map<String, ?> target) {
            String key = uniformName(name);
            boolean exists = floatUniforms.containsKey(key) || intUniforms.containsKey(key) || textures.containsKey(key);
            if (!exists && floatUniforms.size() + intUniforms.size() + textures.size() >= MAX_UNIFORMS) throw new IllegalStateException("A GPU pipeline supports at most " + MAX_UNIFORMS + " uniforms");
            if (target != floatUniforms) floatUniforms.remove(key);
            if (target != intUniforms) intUniforms.remove(key);
            if (target != textures) textures.remove(key);
            return key;
        }

        PipelineState snapshot() {
            synchronized (this) {
                Map<String, float[]> copiedFloats = new LinkedHashMap<>();
                floatUniforms.forEach((name, value) -> copiedFloats.put(name, value.clone()));
                return new PipelineState(shader, drawMode, blend, depth, cull, copiedFloats, new LinkedHashMap<>(intUniforms), new LinkedHashMap<>(textures));
            }
        }
    }

    public record GpuRenderType(GpuPipeline pipeline, GpuSpace space, boolean instanced) {
        public GpuRenderType {
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(space, "space");
        }
    }

    public static final class GpuDrawCall {
        private final PipelineState pipeline;
        private final GpuSpace space;
        private final boolean instanced;
        private final GpuMeshData mesh;

        public GpuDrawCall(GpuRenderType renderType, GpuMesh mesh) {
            this(renderType, Objects.requireNonNull(mesh, "mesh").snapshot());
        }

        public GpuDrawCall(GpuRenderType renderType, GpuMeshData mesh) {
            this.pipeline = renderType.pipeline().snapshot();
            this.space = renderType.space();
            this.instanced = renderType.instanced();
            this.mesh = Objects.requireNonNull(mesh, "mesh");
            if (instanced && mesh.instanceCount() == 0) throw new IllegalStateException("Instanced rendering requires instance data");
            if (!instanced && mesh.instanceCount() != 0) throw new IllegalStateException("Use an instanced render type for meshes with instance data");
        }

        public PipelineState pipeline() {
            return pipeline;
        }

        public GpuSpace space() {
            return space;
        }

        public boolean instanced() {
            return instanced;
        }

        public GpuMeshData mesh() {
            return mesh;
        }
    }

    public static final class PipelineState {
        private final GpuShader shader;
        private final GpuDrawMode drawMode;
        private final GpuBlend blend;
        private final GpuDepth depth;
        private final GpuCull cull;
        private final Map<String, float[]> floatUniforms;
        private final Map<String, Integer> intUniforms;
        private final Map<String, GpuTexture> textures;

        PipelineState(GpuShader shader, GpuDrawMode drawMode, GpuBlend blend, GpuDepth depth, GpuCull cull, Map<String, float[]> floatUniforms, Map<String, Integer> intUniforms, Map<String, GpuTexture> textures) {
            this.shader = shader;
            this.drawMode = drawMode;
            this.blend = blend;
            this.depth = depth;
            this.cull = cull;
            this.floatUniforms = Map.copyOf(floatUniforms);
            this.intUniforms = Map.copyOf(intUniforms);
            this.textures = Map.copyOf(textures);
        }

        public GpuShader shader() {
            return shader;
        }

        public GpuDrawMode drawMode() {
            return drawMode;
        }

        public GpuBlend blend() {
            return blend;
        }

        public GpuDepth depth() {
            return depth;
        }

        public GpuCull cull() {
            return cull;
        }

        public Map<String, float[]> floatUniforms() {
            Map<String, float[]> copy = new LinkedHashMap<>();
            floatUniforms.forEach((name, value) -> copy.put(name, value.clone()));
            return copy;
        }

        public Map<String, Integer> intUniforms() {
            return intUniforms;
        }

        public Map<String, GpuTexture> textures() {
            return textures;
        }
    }

    public static final class GpuMeshData {
        private final GpuFormat format;
        private final boolean indexed;
        private final byte[] vertices;
        private final byte[] instances;
        private final int[] indices;
        private final int vertexCount;
        private final int instanceCount;

        GpuMeshData(GpuFormat format, boolean indexed, byte[] vertices, byte[] instances, int[] indices, int vertexCount, int instanceCount) {
            this.format = format;
            this.indexed = indexed;
            this.vertices = vertices;
            this.instances = instances;
            this.indices = indices;
            this.vertexCount = vertexCount;
            this.instanceCount = instanceCount;
        }

        public GpuFormat format() {
            return format;
        }

        public boolean indexed() {
            return indexed;
        }

        public byte[] vertices() {
            return vertices.clone();
        }

        public int vertexBytes() {
            return vertices.length;
        }

        public void writeVertices(ByteBuffer target) {
            put(target, vertices, "vertex");
        }

        public byte[] instances() {
            return instances.clone();
        }

        public int instanceBytes() {
            return instances.length;
        }

        public void writeInstances(ByteBuffer target) {
            put(target, instances, "instance");
        }

        public int[] indices() {
            return indices.clone();
        }

        public int indexCount() {
            return indices.length;
        }

        public void writeIndices(java.nio.IntBuffer target) {
            Objects.requireNonNull(target, "target");
            if (target.remaining() < indices.length) throw new IllegalArgumentException("Target index buffer is too small");
            target.put(indices);
        }

        public long byteSize() {
            return (long) vertices.length + instances.length + (long) indices.length * Integer.BYTES;
        }

        public int vertexCount() {
            return vertexCount;
        }

        public int instanceCount() {
            return instanceCount;
        }

        private static void put(ByteBuffer target, byte[] source, String kind) {
            Objects.requireNonNull(target, "target");
            if (target.remaining() < source.length) throw new IllegalArgumentException("Target " + kind + " buffer is too small");
            target.put(source);
        }
    }

    private static final class GpuResources {
        private final long bytesPerScript;
        private final Map<String, ScriptGpuResources> scripts = new LinkedHashMap<>();

        private GpuResources(long bytesPerScript) {
            this.bytesPerScript = bytesPerScript;
        }

        private GpuMesh create(String scriptId, GpuFormat format, boolean indexed, int maxBufferBytes) {
            ScriptGpuResources resources;
            synchronized (scripts) {
                resources = scripts.computeIfAbsent(scriptId, ignored -> new ScriptGpuResources(bytesPerScript));
            }
            return resources.create(scriptId, format, indexed, maxBufferBytes);
        }

        private void release(String scriptId, GpuMesh mesh) {
            mesh.requireOwner(scriptId);
            ScriptGpuResources resources;
            synchronized (scripts) {
                resources = scripts.get(scriptId);
            }
            if (resources != null) resources.release(mesh);
            else mesh.release();
        }

        private void reset(String scriptId) {
            ScriptGpuResources resources;
            synchronized (scripts) {
                resources = scripts.remove(scriptId);
            }
            if (resources != null) resources.reset();
        }
    }

    private static final class ScriptGpuResources {
        private static final int MAX_MESHES = 256;
        private final long limit;
        private final Set<GpuMesh> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
        private long used;
        private boolean active = true;

        private ScriptGpuResources(long limit) {
            this.limit = limit;
        }

        private synchronized GpuMesh create(String scriptId, GpuFormat format, boolean indexed, int maxBufferBytes) {
            requireActive();
            if (meshes.size() >= MAX_MESHES) throw new IllegalStateException("GPU mesh handle limit reached; release unused meshes first");
            GpuMesh mesh = new GpuMesh(scriptId, this, format, indexed, maxBufferBytes);
            meshes.add(mesh);
            return mesh;
        }

        private void release(GpuMesh mesh) {
            synchronized (this) {
                if (!meshes.remove(mesh)) {
                    mesh.release();
                    return;
                }
            }
            mesh.release();
        }

        private synchronized void reserve(long bytes) {
            requireActive();
            if (bytes < 0 || bytes > limit - used) throw new IllegalStateException("GPU CPU-memory budget exceeded");
            used += bytes;
        }

        private synchronized void release(long bytes) {
            if (!active || bytes == 0) return;
            if (bytes < 0 || bytes > used) throw new IllegalStateException("GPU CPU-memory accounting underflow");
            used -= bytes;
        }

        private void reset() {
            List<GpuMesh> owned;
            synchronized (this) {
                if (!active) return;
                active = false;
                used = 0;
                owned = List.copyOf(meshes);
                meshes.clear();
            }
            owned.forEach(GpuMesh::release);
        }

        private void requireActive() {
            if (!active) throw new IllegalStateException("GPU resources were reset");
        }
    }

    public interface GpuBackend extends AutoCloseable {
        void submit(String scriptId, GpuRenderType renderType, GpuMesh mesh);

        default String lastError(String scriptId) {
            return "";
        }

        default void reset(String scriptId) {
        }

        @Override
        default void close() {
        }
    }

    private static String shaderSource(String source, String stage, int maxLength) {
        String value = Objects.requireNonNull(source, stage + "Source").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(stage + " shader source cannot be empty");
        if (value.length() > maxLength) throw new IllegalArgumentException(stage + " shader source exceeds the string limit");
        if (!value.contains("void main")) throw new IllegalArgumentException(stage + " shader must define void main");
        return value;
    }

    private static String uniformName(String value) {
        String name = Objects.requireNonNull(value, "name").trim();
        if (name.isEmpty() || name.length() > 256) throw new IllegalArgumentException("Uniform name length is invalid");
        int cursor = 0;
        while (cursor < name.length()) {
            int start = cursor;
            if (!(Character.isLetter(name.charAt(cursor)) || name.charAt(cursor) == '_')) throw new IllegalArgumentException("Invalid GLSL uniform path: " + name);
            for (cursor++; cursor < name.length() && (Character.isLetterOrDigit(name.charAt(cursor)) || name.charAt(cursor) == '_'); cursor++) {
            }
            if (cursor == start) throw new IllegalArgumentException("Invalid GLSL uniform path: " + name);
            while (cursor < name.length() && name.charAt(cursor) == '[') {
                int digits = ++cursor;
                while (cursor < name.length() && Character.isDigit(name.charAt(cursor))) cursor++;
                if (cursor == digits || cursor >= name.length() || name.charAt(cursor++) != ']') throw new IllegalArgumentException("Invalid GLSL uniform path: " + name);
            }
            if (cursor == name.length()) return name;
            if (name.charAt(cursor++) != '.' || cursor == name.length()) throw new IllegalArgumentException("Invalid GLSL uniform path: " + name);
        }
        throw new IllegalArgumentException("Invalid GLSL uniform path: " + name);
    }

    private static boolean identifier(String value) {
        if (value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    private static final class BinaryBuffer {
        private byte[] data = new byte[256];
        private int size;

        void putFloats(float[] values) {
            ensure(values.length * Float.BYTES);
            ByteBuffer.wrap(data, size, values.length * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().put(values);
            size += values.length * Float.BYTES;
        }

        void putInts(int[] values) {
            ensure(values.length * Integer.BYTES);
            ByteBuffer.wrap(data, size, values.length * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer().put(values);
            size += values.length * Integer.BYTES;
        }

        void putBytes(int... values) {
            ensure(values.length);
            for (int value : values) data[size++] = (byte) value;
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            return Arrays.copyOf(data, size);
        }

        void clear() {
            size = 0;
            if (data.length > 256) data = new byte[256];
        }

        private void ensure(int additional) {
            int required = Math.addExact(size, additional);
            if (required <= data.length) return;
            int capacity = data.length;
            while (capacity < required) capacity = Math.max(required, Math.multiplyExact(capacity, 2));
            data = Arrays.copyOf(data, capacity);
        }
    }

    private static final class IntBufferBuilder {
        private int[] data = new int[64];
        private int size;

        void addAll(int[] values) {
            int required = Math.addExact(size, values.length);
            if (required > data.length) {
                int capacity = data.length;
                while (capacity < required) capacity = Math.max(required, Math.multiplyExact(capacity, 2));
                data = Arrays.copyOf(data, capacity);
            }
            System.arraycopy(values, 0, data, size, values.length);
            size = required;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }

        void clear() {
            size = 0;
            if (data.length > 64) data = new int[64];
        }
    }
}
