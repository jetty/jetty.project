package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.annotations.WebSocket;

/**
 * Indicating that the provided Class is not a valid WebSocket as defined by the API.
 * <p>
 * A valid WebSocket should do one of the following:
 * <ul>
 * <li>Implement {@link WebSocketListener}</li>
 * <li>Extend {@link WebSocketAdapter}</li>
 * <li>Declare the {@link WebSocket &#064;WebSocket} annotation on the type</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class InvalidWebSocketException extends WebSocketException
{
    public InvalidWebSocketException(String message)
    {
        super(message);
    }

    public InvalidWebSocketException(String message, Throwable cause)
    {
        super(message,cause);
    }
}
