package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.server.HttpConnection;


/* ------------------------------------------------------------ */
/**
 * Servlet to upgrade connections to WebSocket
 * <p>
 * The request must have the correct upgrade headers, else it is
 * handled as a normal servlet request.
 * <p>
 * The initParameter "bufferSize" can be used to set the buffer size,
 * which is also the max frame byte size (default 8192).
 * 
 */
public abstract class WebSocketServlet extends HttpServlet
{
    WebSocketBuffers _buffers;
       
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.GenericServlet#init()
     */
    @Override
    public void init() throws ServletException
    {
        String bs=getInitParameter("bufferSize");
        _buffers = new WebSocketBuffers(bs==null?8192:Integer.parseInt(bs));
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if ("WebSocket".equals(request.getHeader("Upgrade")) &&
            "HTTP/1.1".equals(request.getProtocol()))
        {
            String protocol=request.getHeader("WebSocket-Protocol");
            WebSocket websocket=doWebSocketConnect(request,protocol);
            
            if (websocket!=null)
            {
                HttpConnection http = HttpConnection.getCurrentConnection();
                ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();
                WebSocketConnection connection = new WebSocketConnection(_buffers,endp,http.getTimeStamp(),websocket);

                String uri=request.getRequestURI();
                String host=request.getHeader("Host");
                String origin=request.getHeader("Origin");
                origin=checkOrigin(request,host,origin);
                
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
            else
            {
                response.sendError(503);
            }
        }
        else
            super.service(request,response);
    }

    protected String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin==null)
            origin=host;
        return origin;
    }
    
    abstract protected WebSocket doWebSocketConnect(HttpServletRequest request,String protocol);
    
    
}
