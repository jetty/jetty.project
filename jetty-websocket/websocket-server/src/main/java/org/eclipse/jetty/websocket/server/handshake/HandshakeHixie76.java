package org.eclipse.jetty.websocket.server.handshake;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.server.WebSocketServer;

/**
 * WebSocket Handshake for spec <a href="https://tools.ietf.org/html/draft-hixie-thewebsocketprotocol-76">Hixie-76 Draft</a>.
 * <p>
 * Most often seen in use by Safari/OSX
 */
public class HandshakeHixie76 implements WebSocketServer.Handshake
{
    /** draft-hixie-thewebsocketprotocol-76 - Sec-WebSocket-Draft */
    public static final int VERSION = 0;

    @Override
    public void doHandshakeResponse(HttpServletRequest request, HttpServletResponse response, List<Extension> extensions, String acceptedSubProtocol)
            throws IOException
    {
        // TODO: implement the Hixie76 handshake?
        throw new IOException("Not implemented yet");
    }
}
