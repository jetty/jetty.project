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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class WebSocketUpgradeHandlerWrapper extends HandlerWrapper implements MappedWebSocketCreator
{
    private NativeWebSocketConfiguration configuration;

    public WebSocketUpgradeHandlerWrapper(ServletContextHandler context)
    {
        this(context, new MappedByteBufferPool());
    }

    public WebSocketUpgradeHandlerWrapper(ServletContextHandler context, ByteBufferPool bufferPool)
    {
        this.configuration = new NativeWebSocketConfiguration(new WebSocketServerFactory(context.getServletContext(), bufferPool));
    }

    @Override
    public void addMapping(PathSpec spec, WebSocketCreator creator)
    {
        this.configuration.addMapping(spec, creator);
    }

    /**
     * Add a mapping.
     *
     * @param spec the path spec to use
     * @param creator the creator for the mapping
     * @deprecated use {@link #addMapping(PathSpec, WebSocketCreator)} instead.
     */
    @Override
    @Deprecated
    public void addMapping(org.eclipse.jetty.websocket.server.pathmap.PathSpec spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }

    @Override
    public void addMapping(String spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }

    @Override
    public boolean removeMapping(String spec)
    {
        return configuration.removeMapping(spec);
    }

    @Override
    public WebSocketCreator getMapping(String target)
    {
        return configuration.getMapping(target);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (configuration.getFactory().isUpgradeRequest(request, response))
        {
            MappedResource<WebSocketCreator> resource = configuration.getMatch(target);
            if (resource == null)
            {
                // no match.
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No websocket endpoint matching path: " + target);
                return;
            }

            WebSocketCreator creator = resource.getResource();

            // Store PathSpec resource mapping as request attribute
            request.setAttribute(PathSpec.class.getName(), resource);

            // We have an upgrade request
            if (configuration.getFactory().acceptWebSocket(creator, request, response))
            {
                // We have a socket instance created
                return;
            }

            // If we reach this point, it means we had an incoming request to upgrade
            // but it was either not a proper websocket upgrade, or it was possibly rejected
            // due to incoming request constraints (controlled by WebSocketCreator)
            if (response.isCommitted())
            {
                // not much we can do at this point.
                return;
            }
        }
        super.handle(target, baseRequest, request, response);
    }
}
