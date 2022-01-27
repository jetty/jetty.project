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

package org.eclipse.jetty.server.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract Handler Container.
 * This is the base class for handlers that may contain other handlers.
 */
public abstract class AbstractHandlerContainer extends AbstractHandler implements HandlerContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractHandlerContainer.class);

    public AbstractHandlerContainer()
    {
    }

    @Override
    public Handler[] getChildHandlers()
    {
        List<Handler> list = new ArrayList<>();
        expandChildren(list, null);
        return list.toArray(new Handler[list.size()]);
    }

    @Override
    public Handler[] getChildHandlersByClass(Class<?> byclass)
    {
        List<Handler> list = new ArrayList<>();
        expandChildren(list, byclass);
        return list.toArray(new Handler[list.size()]);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Handler> T getChildHandlerByClass(Class<T> byclass)
    {
        List<Handler> list = new ArrayList<>();
        expandChildren(list, byclass);
        if (list.isEmpty())
            return null;
        return (T)list.get(0);
    }

    protected void expandChildren(List<Handler> list, Class<?> byClass)
    {
    }

    protected void expandHandler(Handler handler, List<Handler> list, Class<?> byClass)
    {
        if (handler == null)
            return;

        if (byClass == null || byClass.isAssignableFrom(handler.getClass()))
            list.add(handler);

        if (handler instanceof AbstractHandlerContainer)
            ((AbstractHandlerContainer)handler).expandChildren(list, byClass);
        else if (handler instanceof HandlerContainer)
        {
            HandlerContainer container = (HandlerContainer)handler;
            Handler[] handlers = byClass == null ? container.getChildHandlers() : container.getChildHandlersByClass(byClass);
            list.addAll(Arrays.asList(handlers));
        }
    }

    public static <T extends HandlerContainer> T findContainerOf(HandlerContainer root, Class<T> type, Handler handler)
    {
        if (root == null || handler == null)
            return null;

        Handler[] branches = root.getChildHandlersByClass(type);
        if (branches != null)
        {
            for (Handler h : branches)
            {
                @SuppressWarnings("unchecked")
                T container = (T)h;
                Handler[] candidates = container.getChildHandlersByClass(handler.getClass());
                if (candidates != null)
                {
                    for (Handler c : candidates)
                    {
                        if (c == handler)
                            return container;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void setServer(Server server)
    {
        if (server == getServer())
            return;

        if (isStarted())
            throw new IllegalStateException(getState());

        super.setServer(server);
        Handler[] handlers = getHandlers();
        if (handlers != null)
            for (Handler h : handlers)
            {
                h.setServer(server);
            }
    }
}
