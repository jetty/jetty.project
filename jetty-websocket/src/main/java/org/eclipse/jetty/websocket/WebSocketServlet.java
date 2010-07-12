package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/* ------------------------------------------------------------ */
/**
 * Servlet to upgrade connections to WebSocket
 * <p>
 * The request must have the correct upgrade headers, else it is
 * handled as a normal servlet request.
 * <p>
 * The initParameter "bufferSize" can be used to set the buffer size,
 * which is also the max frame byte size (default 8192).
 * <p>
 * The initParameter "maxIdleTime" can be used to set the time in ms
 * that a websocket may be idle before closing (default 300,000).
 * 
 */
public abstract class WebSocketServlet extends HttpServlet
{
    WebSocketFactory _websocket;
       
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.GenericServlet#init()
     */
    @Override
    public void init() throws ServletException
    {
        String bs=getInitParameter("bufferSize");
        _websocket = new WebSocketFactory(bs==null?8192:Integer.parseInt(bs));
        String mit=getInitParameter("maxIdleTime");
        if (mit!=null)
            _websocket.setMaxIdleTime(Integer.parseInt(mit));
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if ("WebSocket".equals(request.getHeader("Upgrade")))
        {
            boolean hixie = request.getHeader("Sec-WebSocket-Key1")!=null;
            
            String protocol=request.getHeader(hixie?"Sec-WebSocket-Protocol":"WebSocket-Protocol");
            if (protocol==null)
                protocol=request.getHeader("Sec-WebSocket-Protocol");
            WebSocket websocket=doWebSocketConnect(request,protocol);

            String host=request.getHeader("Host");
            String origin=request.getHeader("Origin");
            origin=checkOrigin(request,host,origin);
            
            if (websocket!=null)
                _websocket.upgrade(request,response,websocket,origin,protocol);
            else
            {
                if (hixie)
                    response.setHeader("Connection","close");
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
