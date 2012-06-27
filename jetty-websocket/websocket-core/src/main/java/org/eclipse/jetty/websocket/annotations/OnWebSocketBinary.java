package org.eclipse.jetty.websocket.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Annotation for tagging methods to receive Binary message events.
 * <p>
 * Acceptable method patterns.<br>
 * Note: <code><u>methodName</u></code> can be any name you want to use.
 * <ol>
 * <li><code>public void methodName(byte payload[], int offset, int length)</code></li>
 * <li><code>public void methodName({@link ByteBuffer} payload)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, byte payload[], int offset, int length)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, {@link ByteBuffer} payload)</code></li>
 * </ol>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
{ ElementType.METHOD })
public @interface OnWebSocketBinary
{
    /* no config */
}
