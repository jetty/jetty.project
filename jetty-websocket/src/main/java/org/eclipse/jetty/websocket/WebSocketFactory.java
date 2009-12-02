package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.server.HttpConnection;


/* ------------------------------------------------------------ */
/** Factory to create WebSocket connections
 */
public class WebSocketFactory extends WebSocketBuffers
{
    public WebSocketFactory(int bufferSize)
    {
        super(bufferSize);
    }

    /* ------------------------------------------------------------ */
    /** Upgrade the request/response to a WebSocket Connection.
     * <p>This method will not normally return, but will instead throw a
     * UpgradeConnectionException, to exit HTTP handling and initiate
     * WebSocket handling of the connection.
     * @param request The request to upgrade
     * @param response The response to upgrade
     * @param websocket The websocket handler implementation to use
     * @param origin The origin of the websocket connection
     * @param protocol The protocol
     * @throws UpgradeConnectionException Thrown to upgrade the connection
     * @throws IOException
     */
    public void upgrade(HttpServletRequest request,HttpServletResponse response, WebSocket websocket, String origin, String protocol)
     throws UpgradeConnectionException, IOException
     {
        if (!"WebSocket".equals(request.getHeader("Upgrade")))
            throw new IllegalStateException("!Upgrade:websocket");
        if (!"HTTP/1.1".equals(request.getProtocol()))
            throw new IllegalStateException("!HTTP/1.1");
                
        HttpConnection http = HttpConnection.getCurrentConnection();
        ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();
        WebSocketConnection connection = new WebSocketConnection(this,endp,http.getTimeStamp(),websocket);

        String uri=request.getRequestURI();
        String host=request.getHeader("Host");

        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("WebSocket-Origin",origin);
        response.addHeader("WebSocket-Location","ws://"+host+uri);
        if (protocol!=null)
            response.addHeader("WebSocket-Protocol",protocol);
        response.sendError(101,"Web Socket Protocol Handshake");
        response.flushBuffer();

        connection.fill(((HttpParser)http.getParser()).getHeaderBuffer());
        connection.fill(((HttpParser)http.getParser()).getBodyBuffer());

        websocket.onConnect(connection);
        throw new UpgradeConnectionException(connection);
            
     }

}
