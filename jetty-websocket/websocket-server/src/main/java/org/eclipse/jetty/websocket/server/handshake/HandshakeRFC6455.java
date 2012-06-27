package org.eclipse.jetty.websocket.server.handshake;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.AcceptHash;
import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.server.ServletWebSocketRequest;
import org.eclipse.jetty.websocket.server.ServletWebSocketResponse;
import org.eclipse.jetty.websocket.server.WebSocketHandshake;

/**
 * WebSocket Handshake for <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a>.
 */
public class HandshakeRFC6455 implements WebSocketHandshake
{
    /** RFC 6455 - Sec-WebSocket-Version */
    public static final int VERSION = 13;

    @Override
    public void doHandshakeResponse(ServletWebSocketRequest request, ServletWebSocketResponse response, List<Extension> extensions) throws IOException
    {
        String key = request.getHeader("Sec-WebSocket-Key");

        if (key == null)
        {
            throw new IllegalStateException("Missing request header 'Sec-WebSocket-Key'");
        }

        // build response
        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",AcceptHash.hashKey(key));

        if (response.getAcceptedSubProtocol() != null)
        {
            response.addHeader("Sec-WebSocket-Protocol",response.getAcceptedSubProtocol());
        }

        if (extensions != null)
        {
            for (Extension ext : extensions)
            {
                response.addHeader("Sec-WebSocket-Extensions",ext.getConfig().getParameterizedName());
            }
        }

        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
    }
}
