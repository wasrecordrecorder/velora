package io.velora.binding.annotation;

import io.velora.api.function.ScriptThread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface VeloraProperty {
    String name();

    String permission() default "";

    int cost() default 1;

    ScriptThread thread() default ScriptThread.ANY;

    String description() default "";

    String category() default "";

    String returnType() default "";
}
