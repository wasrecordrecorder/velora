package io.velora.internal.semantic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lexical scope for symbol resolution during semantic analysis.
 */
public final class Scope {

    private final Scope parent;
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();

    public Scope() {
        this(null);
    }

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope parent() {
        return parent;
    }

    public void define(Symbol symbol) {
        symbols.put(symbol.name(), symbol);
    }

    public Symbol resolve(String name) {
        Symbol s = symbols.get(name);
        if (s != null) return s;
        if (parent != null) return parent.resolve(name);
        return null;
    }

    public boolean definesLocally(String name) {
        return symbols.containsKey(name);
    }

    public Map<String, Symbol> localSymbols() {
        return symbols;
    }
}
