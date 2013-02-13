//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Create EventDriver implementations.
 */
public class EventDriverFactory
{
    private static final Logger LOG = Log.getLogger(EventDriverFactory.class);
    private final WebSocketPolicy policy;
    private final List<EventDriverImpl> implementations;

    public EventDriverFactory(WebSocketPolicy policy)
    {
        this.policy = policy;
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
     * @param websocket
     *            the websocket instance to wrap. Must either implement {@link WebSocketListener} or be annotated with {@link WebSocket &#064WebSocket}
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
                return impl.create(websocket,policy);
            }
        }

        // Create a clear error message for the developer
        StringBuilder err = new StringBuilder();
        err.append(websocket.getClass().getName());
        err.append(" is not a valid WebSocket object.");
        err.append("  Object must obey one of the following rules: ");

        int len = implementations.size();
        for (int i = 0; i < len; i++)
        {
            EventDriverImpl impl = implementations.get(i);
            if (i > 0)
            {
                err.append("or ");
            }
            err.append('(').append(i + 1).append(") ");
            err.append(impl.describeRule());
        }

        throw new InvalidWebSocketException(err.toString());
    }
}
