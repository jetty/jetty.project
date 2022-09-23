//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
            if (handler == _handler)
                return;

            Handler oldHandler = _handler;
            if (handler != null)
            {
                handler.setServer(server);
                addBean(handler, true);
                if (oldHandler != null && oldHandler.isStarted())
                    handler.start();
            }
            _handler = handler;
            if (oldHandler != null)
                removeBean(oldHandler);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

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
