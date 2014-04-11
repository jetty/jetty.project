//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

/* ------------------------------------------------------------ */
/**
 * A <code>HandlerContainer</code> that allows a hot swap of a wrapped handler.
 * 
 */
public class HotSwapHandler extends AbstractHandlerContainer
{
    private volatile Handler _handler;

    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public HotSwapHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the handlers.
     */
    public Handler getHandler()
    {
        return _handler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the handlers.
     */
    public Handler[] getHandlers()
    {
        return new Handler[]
        { _handler };
    }

    /* ------------------------------------------------------------ */
    /**
     * @param handler
     *            Set the {@link Handler} which should be wrapped.
     */
    public void setHandler(Handler handler)
    {
        if (handler == null)
            throw new IllegalArgumentException("Parameter handler is null.");
        try
        {
            Handler old_handler = _handler;
            _handler = handler;
            Server server = getServer();
            handler.setServer(server);
            addBean(handler);

            if (server != null)
                server.getContainer().update(this,old_handler,handler,"handler");

            // if there is an old handler and it was started, stop it
            if (old_handler != null)
            {
                removeBean(old_handler);
            }

        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.EventHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_handler != null && isStarted())
        {
            _handler.handle(target,baseRequest,request,response);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setServer(Server server)
    {
        Server old_server = getServer();
        if (server == old_server)
            return;

        if (isRunning())
            throw new IllegalStateException(RUNNING);

        super.setServer(server);

        Handler h = getHandler();
        if (h != null)
            h.setServer(server);

        if (server != null && server != old_server)
            server.getContainer().update(this,null,_handler,"handler");
    }

    /* ------------------------------------------------------------ */
    @SuppressWarnings(
    { "rawtypes", "unchecked" })
    @Override
    protected Object expandChildren(Object list, Class byClass)
    {
        return expandHandler(_handler,list,byClass);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        Handler child = getHandler();
        if (child != null)
        {
            setHandler(null);
            child.destroy();
        }
        super.destroy();
    }
}
