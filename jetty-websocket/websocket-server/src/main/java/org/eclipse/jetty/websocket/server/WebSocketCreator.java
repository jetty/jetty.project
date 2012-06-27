package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.websocket.extensions.Extension;

/**
 * Abstract WebSocket creator interface.
 * <p>
 * Should you desire filtering of the WebSocket object creation due to criteria such as origin or sub-protocol, then you will be required to implement a custom
 * WebSocketCreator implementation.
 * <p>
 * This has been moved from the WebSocketServlet to a standalone class managed by the {@link WebSocketServerFactory} due to need of WebSocket {@link Extension}s
 * that require the ability to create new websockets (such as the mux extension)
 */
public interface WebSocketCreator
{
    /**
     * Create a websocket from the incoming request.
     * 
     * @param req
     *            the request details
     * @return a websocket object to use, or null if no websocket should be created from this request.
     */
    Object createWebSocket(WebSocketRequest req, WebSocketResponse resp);
}
