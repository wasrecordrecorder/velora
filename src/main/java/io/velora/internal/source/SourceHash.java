package io.velora.internal.source;

public final class SourceHash {
    private SourceHash() {}

    public static String compute(String content) {
        if (content == null || content.isEmpty()) {
            return "0";
        }
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean matches(String content, String expectedHash) {
        return compute(content).equals(expectedHash);
    }
}
