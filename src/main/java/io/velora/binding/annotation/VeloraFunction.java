package io.velora.binding.annotation;

import io.velora.api.function.ScriptThread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface VeloraFunction {
    String name();

    String permission() default "";

    ScriptThread thread() default ScriptThread.ANY;

    boolean suspending() default false;

    int cost() default 1;

    String description() default "";

    String category() default "";

    String returnType() default "";
}
