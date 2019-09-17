//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Abstract Handler Container.
 * This is the base class for handlers that may contain other handlers.
 */
public abstract class AbstractHandlerContainer extends AbstractHandler implements HandlerContainer
{
    private static final Logger LOG = Log.getLogger(AbstractHandlerContainer.class);

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
            throw new IllegalStateException(STARTED);

        super.setServer(server);
        Handler[] handlers = getHandlers();
        if (handlers != null)
            for (Handler h : handlers)
            {
                h.setServer(server);
            }
    }

    /**
     * Shutdown nested Gracefule handlers
     */
    protected void doShutdown() throws MultiException
    {
        final MultiException mex = new MultiException();

        // tell the graceful handlers that we are shutting down
        // TODO some how need to order these!!!
        // TODO perhaps Graceful can define phases and they can belong to phases?
        // TODO for now will get them in a known order
        List<Graceful> gracefuls = new ArrayList<>(getContainedBeans(Connector.class));
        getContainedBeans(ContextHandler.class).forEach(gracefuls::add);
        getContainedBeans(StatisticsHandler.class).forEach(gracefuls::add);
        getContainedBeans(ManagedSelector.class).forEach(gracefuls::add);
        getContainedBeans(Graceful.class).forEach(g ->
        {
            if (!gracefuls.contains(g))
                gracefuls.add(g);
        });
        gracefuls.stream().forEach(System.err::println);

        long stopTimeout = getStopTimeout();
        for (Graceful graceful : gracefuls)
        {
            if (graceful instanceof AbstractLifeCycle)
                ((AbstractLifeCycle)graceful).setStopTimeout(stopTimeout);

            if (LOG.isDebugEnabled())
                LOG.debug("shutdown {}", graceful);
            Future future = graceful.shutdown();
            if (stopTimeout > 0)
            {
                long start = System.nanoTime();
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("graceful {} {} {}", graceful, future, stopTimeout);
                    future.get(stopTimeout,TimeUnit.MILLISECONDS);
                }
                catch (Throwable e)
                {
                    // If the future is also a callback, fail it here (rather than cancel) so we can capture the exception
                    if (future instanceof Callback && !future.isDone())
                        ((Callback)future).failed(e);
                    mex.add(e);
                }
                stopTimeout -= TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }
            if (!future.isDone())
                future.cancel(true);
        }

        if (mex != null)
            mex.ifExceptionThrowMulti();
    }
}
