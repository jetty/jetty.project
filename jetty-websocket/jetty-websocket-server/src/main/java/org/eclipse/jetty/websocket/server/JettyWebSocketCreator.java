package org.eclipse.jetty.websocket.server;


/**
 * Abstract WebSocket creator interface.
 * <p>
 * Should you desire filtering of the WebSocket object creation due to criteria such as origin or sub-protocol, then you will be required to implement a custom
 * WebSocketCreator implementation.
 * </p>
 */
public interface JettyWebSocketCreator
{
    /**
     * Create a websocket from the incoming request.
     *
     * @param req  the request details
     * @param resp the response details
     * @return a websocket object to use, or null if no websocket should be created from this request.
     */
    Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp);
}
