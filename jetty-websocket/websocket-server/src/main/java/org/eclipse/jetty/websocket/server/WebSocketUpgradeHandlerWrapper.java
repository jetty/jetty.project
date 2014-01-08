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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.websocket.server.pathmap.PathMappings;
import org.eclipse.jetty.websocket.server.pathmap.PathMappings.MappedResource;
import org.eclipse.jetty.websocket.server.pathmap.PathSpec;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class WebSocketUpgradeHandlerWrapper extends HandlerWrapper implements MappedWebSocketCreator
{
    private PathMappings<WebSocketCreator> pathmap = new PathMappings<>();
    private final WebSocketServerFactory factory;

    public WebSocketUpgradeHandlerWrapper()
    {
        this(new MappedByteBufferPool());
    }
    
    public WebSocketUpgradeHandlerWrapper(ByteBufferPool bufferPool)
    {
        factory = new WebSocketServerFactory(bufferPool);
    }

    @Override
    public void addMapping(PathSpec spec, WebSocketCreator creator)
    {
        pathmap.put(spec,creator);
    }

    @Override
    public PathMappings<WebSocketCreator> getMappings()
    {
        return pathmap;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (factory.isUpgradeRequest(request,response))
        {
            MappedResource<WebSocketCreator> resource = pathmap.getMatch(target);
            if (resource == null)
            {
                // no match.
                response.sendError(HttpServletResponse.SC_NOT_FOUND,"No websocket endpoint matching path: " + target);
                return;
            }

            WebSocketCreator creator = resource.getResource();

            // Store PathSpec resource mapping as request attribute
            request.setAttribute(PathSpec.class.getName(),resource);

            // We have an upgrade request
            if (factory.acceptWebSocket(creator,request,response))
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
        super.handle(target,baseRequest,request,response);
    }
}
