package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

public abstract class WebSocketHandler extends HandlerWrapper
{
    private WebSocketBuffers _buffers = new WebSocketBuffers(8192);
    private int _bufferSize=8192;
    
    
    /* ------------------------------------------------------------ */
    /** Get the bufferSize.
     * @return the bufferSize
     */
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /* ------------------------------------------------------------ */
    /** Set the bufferSize.
     * @param bufferSize the bufferSize to set
     */
    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _buffers=new WebSocketBuffers(_bufferSize);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _buffers=null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
        {
            super.handle(target,baseRequest,request,response);
        }
    }

    protected String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin==null)
            origin=host;
        return origin;
    }

    abstract protected WebSocket doWebSocketConnect(HttpServletRequest request,String protocol);
    
}
