package io.velora.api.registry;

import io.velora.api.type.VeloraType;

import java.util.Collection;
import java.util.List;

/**
 * Registry for host constants exposed to scripts (e.g. {@code Blocks.DIAMOND_ORE}).
 *
 * <p>Constants are organised into namespaces and referenced in scripts as
 * {@code Namespace.MEMBER}. They are immutable values available without import.
 */
public interface ConstantRegistry {

    /** Register a constant under a namespace. */
    void register(String namespace, String member, VeloraType type, Object value);

    /** Find a constant by namespace and member. */
    Constant find(String namespace, String member);

    /** All constants in a namespace. */
    List<Constant> namespace(String namespace);

    /** All registered namespaces. */
    Collection<String> namespaces();

    /** All registered constants. */
    List<Constant> all();

    boolean isFrozen();

    /** An immutable constant value. */
    record Constant(String namespace, String member, VeloraType type, Object value) {
        public String qualifiedName() {
            return namespace + "." + member;
        }
    }
}
