package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;

public class WebSocketServletConnectionD06 extends WebSocketConnectionD06 implements WebSocketServletConnection
{
    public WebSocketServletConnectionD06(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol)
            throws IOException
    {
        super(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol);
    }

    /* ------------------------------------------------------------ */
    public void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException
    {
        String key = request.getHeader("Sec-WebSocket-Key");

        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",hashKey(key));
        if (subprotocol!=null)
        {
            response.addHeader("Sec-WebSocket-Protocol",subprotocol);
        }
        
        response.sendError(101);

        if (_onFrame!=null)
        {
            _onFrame.onHandshake(_connection);
        }
        _webSocket.onOpen(_connection);
    }
}
