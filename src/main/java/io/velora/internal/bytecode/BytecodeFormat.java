package io.velora.internal.bytecode;

public final class BytecodeFormat {
    public static final int MAGIC = BytecodeHeader.MAGIC;
    public static final int VERSION = BytecodeHeader.FORMAT_VERSION;

    private BytecodeFormat() {}

    public static boolean isValidMagic(int magic) {
        return magic == MAGIC;
    }

    public static boolean isSupportedVersion(int version) {
        return version == VERSION;
    }
}
