package com.tinniestudio.api.shared.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller class to be excluded from {@link SuccessResponseWrapper}.
 * Apply at class level only. Document exclusions in contracts/excluded-endpoints.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SkipResponseWrapper {
}
