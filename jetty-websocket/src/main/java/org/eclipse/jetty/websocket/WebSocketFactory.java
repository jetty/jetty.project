// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.server.HttpConnection;


/* ------------------------------------------------------------ */
/** Factory to create WebSocket connections
 */
public class WebSocketFactory
{
    private WebSocketBuffers _buffers;
    private int _maxIdleTime=300000;

    /* ------------------------------------------------------------ */
    public WebSocketFactory()
    {
        _buffers=new WebSocketBuffers(8192);
    }

    /* ------------------------------------------------------------ */
    public WebSocketFactory(int bufferSize)
    {
        _buffers=new WebSocketBuffers(bufferSize);
    }

    /* ------------------------------------------------------------ */
    /** Get the maxIdleTime.
     * @return the maxIdleTime
     */
    public long getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /** Set the maxIdleTime.
     * @param maxIdleTime the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /** Get the bufferSize.
     * @return the bufferSize
     */
    public int getBufferSize()
    {
        return _buffers.getBufferSize();
    }

    /* ------------------------------------------------------------ */
    /** Set the bufferSize.
     * @param bufferSize the bufferSize to set
     */
    public void setBufferSize(int bufferSize)
    {
        if (bufferSize!=getBufferSize())
            _buffers=new WebSocketBuffers(bufferSize);
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
     * @param subprotocol The protocol
     * @throws UpgradeConnectionException Thrown to upgrade the connection
     * @throws IOException
     */
    public void upgrade(HttpServletRequest request,HttpServletResponse response, WebSocket websocket, String origin, String subprotocol)
     throws IOException
     {
        if (!"WebSocket".equals(request.getHeader("Upgrade")))
            throw new IllegalStateException("!Upgrade:websocket");
        if (!"HTTP/1.1".equals(request.getProtocol()))
            throw new IllegalStateException("!HTTP/1.1");
                
        int draft=request.getIntHeader("Sec-WebSocket-Draft");
        HttpConnection http = HttpConnection.getCurrentConnection();
        ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();
        
        final WebSocketConnection connection;
        switch(draft)
        {
            default:
                connection=new WebSocketConnectionD00(websocket,endp,_buffers,http.getTimeStamp(), _maxIdleTime,draft);
        }
        
        // Let the connection finish processing the handshake
        connection.handshake(request,response, origin, subprotocol);
        response.flushBuffer();

        // Give the connection any unused data from the HTTP connection.
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getHeaderBuffer());
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getBodyBuffer());

        // Tell jetty about the new connection 
        request.setAttribute("org.eclipse.jetty.io.Connection",connection);
     }
}
