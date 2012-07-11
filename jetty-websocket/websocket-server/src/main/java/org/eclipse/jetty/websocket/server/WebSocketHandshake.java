package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.websocket.api.Extension;

public interface WebSocketHandshake
{
    /**
     * Formulate a WebSocket upgrade handshake response.
     * 
     * @param request
     * @param response
     * @param extensions
     * @param acceptedSubProtocol
     */
    public void doHandshakeResponse(ServletWebSocketRequest request, ServletWebSocketResponse response, List<Extension> extensions) throws IOException;
}