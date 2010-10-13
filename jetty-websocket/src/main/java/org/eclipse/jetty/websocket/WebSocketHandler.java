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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

public abstract class WebSocketHandler extends HandlerWrapper
{
    private WebSocketFactory _websocket;
    private int _bufferSize=8192;
    private int _maxIdleTime=-1;
    
    
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
    /** Get the maxIdleTime.
     * @return the maxIdleTime
     */
    public int getMaxIdleTime()
    {
        return (int)(_websocket==null?_maxIdleTime:_websocket.getMaxIdleTime());
    }

    /* ------------------------------------------------------------ */
    /** Set the maxIdleTime.
     * @param maxIdleTime the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
        if (_websocket!=null)
            _websocket.setMaxIdleTime(maxIdleTime);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _websocket=new WebSocketFactory(_bufferSize);
        if (_maxIdleTime>=0)
            _websocket.setMaxIdleTime(_maxIdleTime);
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
        _websocket=null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if ("WebSocket".equals(request.getHeader("Upgrade")))
        {
            String subprotocol=request.getHeader(request.getHeader("Sec-WebSocket-Key1")!=null?"Sec-WebSocket-Protocol":"WebSocket-Protocol");
            WebSocket websocket=doWebSocketConnect(request,subprotocol);

            String host=request.getHeader("Host");
            String origin=request.getHeader("Origin");
            origin=checkOrigin(request,host,origin);

            if (websocket!=null)
                _websocket.upgrade(request,response,websocket,origin,subprotocol);
            else
                response.sendError(503);
        }
        else
        {
            super.handle(target,baseRequest,request,response);
        }
    }
    
    /* ------------------------------------------------------------ */
    protected String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin==null)
            origin=host;
        return origin;
    }
    /* ------------------------------------------------------------ */

    abstract protected WebSocket doWebSocketConnect(HttpServletRequest request,String protocol);
    
}
