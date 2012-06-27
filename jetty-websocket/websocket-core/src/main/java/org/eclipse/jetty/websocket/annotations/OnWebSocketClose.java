package org.eclipse.jetty.websocket.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Annotation for tagging methods to receive connection close events.
 * <p>
 * Acceptable method patterns.<br>
 * Note: <code>methodName</code> can be any name you want to use.
 * <ol>
 * <li><code>public void methodName(int statusCode, String reason)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, int statusCode, String reason)</code></li>
 * </ol>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
{ ElementType.METHOD })
public @interface OnWebSocketClose
{
    /* no config */
}
