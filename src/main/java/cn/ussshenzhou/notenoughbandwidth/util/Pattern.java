package cn.ussshenzhou.notenoughbandwidth.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a preprocessor remap pattern (declared via
 * {@code preprocess.patternAnnotation} in build.gradle). The annotated method's
 * per-version bodies teach the remapper how to rewrite matching call-site
 * expressions when deriving other Minecraft versions from the main one.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Pattern {
}
