package org.eclipse.jetty.websocket.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tags a POJO as being a WebSocket class.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
{ ElementType.TYPE })
public @interface WebSocket
{
    int maxBinarySize() default 8192;

    int maxBufferSize() default 8192;

    int maxIdleTime() default 300000;

    int maxTextSize() default 8192;
}
