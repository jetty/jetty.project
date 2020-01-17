//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

/**
 * A <code>HandlerContainer</code> that allows a hot swap of a wrapped handler.
 */
public class HotSwapHandler extends AbstractHandlerContainer
{
    private volatile Handler _handler;

    /**
     *
     */
    public HotSwapHandler()
    {
    }

    /**
     * @return Returns the handlers.
     */
    public Handler getHandler()
    {
        return _handler;
    }

    /**
     * @return Returns the handlers.
     */
    @Override
    public Handler[] getHandlers()
    {
        Handler handler = _handler;
        if (handler == null)
            return new Handler[0];
        return new Handler[]{handler};
    }

    /**
     * @param handler Set the {@link Handler} which should be wrapped.
     */
    public void setHandler(Handler handler)
    {
        try
        {
            Server server = getServer();
            if (handler != null)
                handler.setServer(server);
            updateBean(_handler, handler, true);
            _handler = handler;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    /*
     * @see org.eclipse.jetty.server.server.EventHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Handler handler = _handler;
        if (handler != null && isStarted() && handler.isStarted())
        {
            handler.handle(target, baseRequest, request, response);
        }
    }

    @Override
    protected void expandChildren(List<Handler> list, Class<?> byClass)
    {
        Handler handler = _handler;
        if (handler != null)
            expandHandler(handler, list, byClass);
    }

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
