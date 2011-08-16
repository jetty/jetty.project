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

public abstract class WebSocketHandler extends HandlerWrapper implements WebSocketFactory.Acceptor
{
    private WebSocketFactory _webSocketFactory;
    private int _bufferSize=64*1024;
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
        return (int)(_webSocketFactory==null?_maxIdleTime:_webSocketFactory.getMaxIdleTime());
    }

    /* ------------------------------------------------------------ */
    /** Set the maxIdleTime.
     * @param maxIdleTime the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
        if (_webSocketFactory!=null)
            _webSocketFactory.setMaxIdleTime(maxIdleTime);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _webSocketFactory=new WebSocketFactory(this,_bufferSize);
        if (_maxIdleTime>=0)
            _webSocketFactory.setMaxIdleTime(_maxIdleTime);
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
        _webSocketFactory=null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_webSocketFactory.acceptWebSocket(request,response) || response.isCommitted())
            return;
        super.handle(target,baseRequest,request,response);
    }
    
    /* ------------------------------------------------------------ */
    public boolean checkOrigin(HttpServletRequest request, String origin)
    {
        return true;
    }
    
}
