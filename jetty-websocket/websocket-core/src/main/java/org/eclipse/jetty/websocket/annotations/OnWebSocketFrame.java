package org.eclipse.jetty.websocket.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.BaseFrame;

/**
 * (ADVANCED) Annotation for tagging methods to receive frame events.
 * <p>
 * Note: any frame derived from {@link BaseFrame} is acceptable to use as the last parameter here.
 * <p>
 * Acceptable method patterns.<br>
 * Note: <code>methodName</code> can be any name you want to use.
 * <ol>
 * <li><code>public void methodName({@link BaseFrame} frame)</code></li>
 * <li><code>public void methodName(BinaryFrame frame)</code></li>
 * <li><code>public void methodName(CloseFrame frame)</code></li>
 * <li><code>public void methodName(ControlFrame frame)</code></li>
 * <li><code>public void methodName(DataFrame frame)</code></li>
 * <li><code>public void methodName(PingFrame frame)</code></li>
 * <li><code>public void methodName(PongFrame frame)</code></li>
 * <li><code>public void methodName(TextFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, {@link BaseFrame} frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, BinaryFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, CloseFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, ControlFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, DataFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, PingFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, PongFrame frame)</code></li>
 * <li><code>public void methodName({@link WebSocketConnection} conn, TextFrame frame)</code></li>
 * </ol>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
{ ElementType.METHOD })
public @interface OnWebSocketFrame
{
    /* no config */
}
