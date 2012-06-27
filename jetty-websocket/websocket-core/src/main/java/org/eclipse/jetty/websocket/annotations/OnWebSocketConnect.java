package org.eclipse.jetty.websocket.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Annotation for tagging methods to receive connection open events.
 * <p>
 * Only 1 acceptable method pattern for this annotation.<br>
 * Note: <code>methodName</code> can be any name you want to use.
 * <ol>
 * <li><code>public void methodName({@link WebSocketConnection} conn)</code></li>
 * </ol>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
{ ElementType.METHOD })
public @interface OnWebSocketConnect
{
    /* no config */
}
