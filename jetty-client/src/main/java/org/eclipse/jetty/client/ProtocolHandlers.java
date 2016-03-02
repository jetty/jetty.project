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

package org.eclipse.jetty.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

/**
 * <p>A container for {@link ProtocolHandler}s accessible from {@link HttpClient#getProtocolHandlers()}.</p>
 */
public class ProtocolHandlers
{
    private final Map<String, ProtocolHandler> handlers = new LinkedHashMap<>();

    protected ProtocolHandlers()
    {
    }

    /**
     * <p>Stores the given {@code protocolHandler} in this container.</p>
     * <p>If a protocol handler with the same name exists, it is
     * replaced by the given one, and the existing returned.</p>
     *
     * @param protocolHandler the protocol handler to store
     * @return the existing protocol handler with the same name,
     * or null if no protocol handler with that name was already stored
     * @see #remove(String)
     */
    public ProtocolHandler put(ProtocolHandler protocolHandler)
    {
        return handlers.put(protocolHandler.getName(), protocolHandler);
    }

    /**
     * <p>Removes the protocol handler with the given name.</p>
     *
     * @param name the name of the protocol handler to remove
     * @return the removed protocol handler, or null if no
     * protocol handler with that name was already stored
     * @see #put(ProtocolHandler)
     * @see #clear()
     */
    public ProtocolHandler remove(String name)
    {
        return handlers.remove(name);
    }

    /**
     * <p>Removes all protocol handlers from this container.</p>
     */
    public void clear()
    {
        handlers.clear();
    }

    /**
     * <p>Finds the first protocol handler that
     * {@link ProtocolHandler#accept(Request, Response) accepts}
     * the given request and response.</p>
     *
     * @param request  the request to accept
     * @param response the response to accept
     * @return the protocol handler that accepted the request and response,
     * or null if none of the protocol handlers accepted the request and response
     */
    public ProtocolHandler find(Request request, Response response)
    {
        for (ProtocolHandler handler : handlers.values())
        {
            if (handler.accept(request, response))
                return handler;
        }
        return null;
    }
}
