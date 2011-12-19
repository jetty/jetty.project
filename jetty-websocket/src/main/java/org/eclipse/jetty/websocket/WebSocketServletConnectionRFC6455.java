package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;

public class WebSocketServletConnectionRFC6455 extends WebSocketConnectionRFC6455 implements WebSocketServletConnection
{
    public WebSocketServletConnectionRFC6455(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol,
            List<Extension> extensions, int draft, MaskGen maskgen) throws IOException
    {
        super(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol,extensions,draft,maskgen);
    }

    public WebSocketServletConnectionRFC6455(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol,
            List<Extension> extensions, int draft) throws IOException
    {
        super(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol,extensions,draft);
    }

    /* ------------------------------------------------------------ */
    public void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException
    {
        String key = request.getHeader("Sec-WebSocket-Key");

        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",hashKey(key));
        if (subprotocol != null)
        {
            response.addHeader("Sec-WebSocket-Protocol",subprotocol);
        }

        for (Extension ext : getExtensions())
        {
            response.addHeader("Sec-WebSocket-Extensions",ext.getParameterizedName());
        }

        response.sendError(101);

        onFrameHandshake();
        onWebSocketOpen();
    }
}
