package io.velora.internal.bytecode;

public record BytecodeHeader(
        int magic,
        int formatVersion,
        String scriptId,
        int scriptRevision,
        String schemaHash,
        int entryCount,
        long checksum
) {
    public static final int MAGIC = 0x564C5343;
    public static final int FORMAT_VERSION = 1;

    public static BytecodeHeader create(String scriptId, int revision, String schemaHash, int entryCount) {
        return new BytecodeHeader(MAGIC, FORMAT_VERSION, scriptId, revision, schemaHash, entryCount, 0L);
    }
}
