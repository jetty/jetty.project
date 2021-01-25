//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.events;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

/**
 * Create EventDriver implementations.
 */
public class EventDriverFactory
{
    private static final Logger LOG = Log.getLogger(EventDriverFactory.class);
    private final WebSocketContainerScope containerScope;
    private final List<EventDriverImpl> implementations;

    public EventDriverFactory(WebSocketContainerScope containerScope)
    {
        this.containerScope = containerScope;
        this.implementations = new ArrayList<>();

        addImplementation(new JettyListenerImpl());
        addImplementation(new JettyAnnotatedImpl());
    }

    public void addImplementation(EventDriverImpl impl)
    {
        if (implementations.contains(impl))
        {
            LOG.warn("Ignoring attempt to add duplicate EventDriverImpl: " + impl);
            return;
        }

        implementations.add(impl);
    }

    public void clearImplementations()
    {
        this.implementations.clear();
    }

    protected String getClassName(Object websocket)
    {
        return websocket.getClass().getName();
    }

    public List<EventDriverImpl> getImplementations()
    {
        return implementations;
    }

    public boolean removeImplementation(EventDriverImpl impl)
    {
        return this.implementations.remove(impl);
    }

    @Override
    public String toString()
    {
        StringBuilder msg = new StringBuilder();
        msg.append(this.getClass().getSimpleName());
        msg.append("[implementations=[");
        boolean delim = false;
        for (EventDriverImpl impl : implementations)
        {
            if (delim)
            {
                msg.append(',');
            }
            msg.append(impl.toString());
            delim = true;
        }
        msg.append("]");
        return msg.toString();
    }

    /**
     * Wrap the given WebSocket object instance in a suitable EventDriver
     *
     * @param websocket the websocket instance to wrap. Must either implement {@link WebSocketListener} or be annotated with {@link WebSocket &#064;WebSocket}
     * @return appropriate EventDriver for this websocket instance.
     */
    public EventDriver wrap(Object websocket)
    {
        if (websocket == null)
        {
            throw new InvalidWebSocketException("null websocket object");
        }

        for (EventDriverImpl impl : implementations)
        {
            if (impl.supports(websocket))
            {
                try
                {
                    return impl.create(websocket, containerScope.getPolicy().clonePolicy());
                }
                catch (Throwable e)
                {
                    throw new InvalidWebSocketException("Unable to create websocket", e);
                }
            }
        }

        // Create a clear error message for the developer
        StringBuilder err = new StringBuilder();
        err.append(getClassName(websocket));
        err.append(" is not a valid WebSocket object.");
        err.append("  Object must obey one of the following rules: ");

        int len = implementations.size();
        for (int i = 0; i < len; i++)
        {
            EventDriverImpl impl = implementations.get(i);
            if (i > 0)
            {
                err.append(" or ");
            }
            err.append("\n(").append(i + 1).append(") ");
            err.append(impl.describeRule());
        }

        throw new InvalidWebSocketException(err.toString());
    }
}
