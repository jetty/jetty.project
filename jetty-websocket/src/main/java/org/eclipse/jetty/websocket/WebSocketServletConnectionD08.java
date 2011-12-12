package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;

public class WebSocketServletConnectionD08 extends WebSocketConnectionD08 implements WebSocketServletConnection
{
    public WebSocketServletConnectionD08(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol,
            List<Extension> extensions, int draft, MaskGen maskgen) throws IOException
    {
        super(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol,extensions,draft,maskgen);
    }

    public WebSocketServletConnectionD08(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol,
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

        for (Extension ext : _extensions)
        {
            response.addHeader("Sec-WebSocket-Extensions",ext.getParameterizedName());
        }

        response.sendError(101);

        if (_onFrame != null)
        {
            _onFrame.onHandshake(_connection);
        }
        _webSocket.onOpen(_connection);
    }
}
