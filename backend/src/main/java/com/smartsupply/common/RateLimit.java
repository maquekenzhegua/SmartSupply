package com.smartsupply.common;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    int permitsPerMinute() default 30;
    String key() default "";
}
