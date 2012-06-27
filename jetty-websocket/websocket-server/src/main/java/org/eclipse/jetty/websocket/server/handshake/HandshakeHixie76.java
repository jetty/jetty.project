package org.eclipse.jetty.websocket.server.handshake;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.server.ServletWebSocketRequest;
import org.eclipse.jetty.websocket.server.ServletWebSocketResponse;
import org.eclipse.jetty.websocket.server.WebSocketHandshake;

/**
 * WebSocket Handshake for spec <a href="https://tools.ietf.org/html/draft-hixie-thewebsocketprotocol-76">Hixie-76 Draft</a>.
 * <p>
 * Most often seen in use by Safari/OSX
 */
public class HandshakeHixie76 implements WebSocketHandshake
{
    /** draft-hixie-thewebsocketprotocol-76 - Sec-WebSocket-Draft */
    public static final int VERSION = 0;

    @Override
    public void doHandshakeResponse(ServletWebSocketRequest request, ServletWebSocketResponse response, List<Extension> extensions) throws IOException
    {
        // TODO: implement the Hixie76 handshake?
        throw new IOException("Not implemented yet");
    }
}
