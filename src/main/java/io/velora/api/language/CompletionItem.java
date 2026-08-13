package io.velora.api.language;

public record CompletionItem(
        String label,
        String detail,
        String documentation,
        String insertText,
        CompletionKind kind
) {
    public CompletionItem {
        java.util.Objects.requireNonNull(label);
        kind = kind == null ? CompletionKind.TEXT : kind;
    }

    public static CompletionItem of(String label, CompletionKind kind) {
        return new CompletionItem(label, null, null, label, kind);
    }

    public enum CompletionKind {
        TEXT, KEYWORD, FUNCTION, PROPERTY, SETTING, TYPE, ENUM_CONSTANT, CONSTANT, NAMESPACE, SNIPPET
    }
}
