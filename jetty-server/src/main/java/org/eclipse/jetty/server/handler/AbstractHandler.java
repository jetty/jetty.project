//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;


import java.io.IOException;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** AbstractHandler.
 */
@ManagedObject("Jetty Handler")
public abstract class AbstractHandler extends ContainerLifeCycle implements Handler
{
    private static final Logger LOG = Log.getLogger(AbstractHandler.class);

    private Server _server;
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public AbstractHandler()
    {
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (baseRequest.getDispatcherType()==DispatcherType.ERROR)
            doError(target,baseRequest,request,response);
        else
            doHandle(target,baseRequest,request,response);
    }    

    /* ------------------------------------------------------------ */
    protected void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
    }
    
    /* ------------------------------------------------------------ */
    protected void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Object o = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = (o instanceof Integer)?((Integer)o).intValue():(o!=null?Integer.valueOf(o.toString()):500);
        o = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        String reason = o!=null?o.toString():null;
        
        response.sendError(code,reason);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.LifeCycle#start()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("starting {}",this);
        if (_server==null)
            LOG.warn("No Server set for {}",this);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.LifeCycle#stop()
     */
    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stopping {}",this);
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setServer(Server server)
    {
        if (_server==server)
            return;
        if (isStarted())
            throw new IllegalStateException(STARTED);
        _server=server;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        super.destroy();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dumpThis(Appendable out) throws IOException
    {
        out.append(toString()).append(" - ").append(getState()).append('\n');
    }
    
}
