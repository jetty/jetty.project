//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.servlet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.common.FrameHandlerFactory;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.common.WebSocketContainerContext;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

/**
 * Working with WebSocket's within a ServletContext
 */
public class ServletContextWebSocketContainer extends ContainerLifeCycle implements WebSocketContainerContext
{
    public static ServletContextWebSocketContainer get(ServletContext context) throws ServletException
    {
        try
        {
            final String ATTR = ServletContextWebSocketContainer.class.getName();

            ServletContextWebSocketContainer wsContainer = (ServletContextWebSocketContainer) context.getAttribute(ATTR);
            if (wsContainer == null)
            {
                wsContainer = new ServletContextWebSocketContainer(context);
                context.setAttribute(ATTR, wsContainer);
                ContextHandler contextHandler = ContextHandler.getContextHandler(context);
                if (contextHandler != null)
                {
                    contextHandler.addManaged(wsContainer);
                }
                else
                {
                    wsContainer.start();
                }
            }
            return wsContainer;
        }
        catch (ServletException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new ServletException("Unable to get ServletContextWebSocketContainer", t);
        }
    }

    private static final Logger LOG = Log.getLogger(ServletContextWebSocketContainer.class);
    /** The context that the factory resides in (can only exist in 1 ServletContext at a time) */
    private final ServletContext context;
    private WebSocketPolicy policy;
    private WebSocketExtensionRegistry extensionRegistry;
    private DecoratedObjectFactory objectFactory;
    private Executor executor; // TODO: obtain from Server / ServletContext ?
    private ByteBufferPool bufferPool; // TODO: obtain from Server / ServletContext / Connector ?
    private List<FrameHandlerFactory> factories = new ArrayList<>();

    public ServletContextWebSocketContainer(ServletContext context)
    {
        this(context, null, null, null, null);
    }

    public ServletContextWebSocketContainer(ServletContext context, WebSocketPolicy policy, WebSocketExtensionRegistry extensionRegistry, Executor executor, ByteBufferPool bufferPool)
    {
        Objects.requireNonNull(context, "ServletContext cannot be null");
        this.context = context;

        this.policy = policy;
        this.extensionRegistry = extensionRegistry;
        // Attempt to pull from known location at ServletContext/ServletContextHandler
        this.objectFactory = (DecoratedObjectFactory) context.getAttribute(DecoratedObjectFactory.ATTR);
        this.executor = executor;
        this.bufferPool = bufferPool;

        if (this.policy == null)
            this.policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        if (this.objectFactory == null)
            this.objectFactory = new DecoratedObjectFactory();
        if (this.extensionRegistry == null)
            this.extensionRegistry = new WebSocketExtensionRegistry();
        if (this.executor == null)
            this.executor = new QueuedThreadPool();
        if (this.bufferPool == null)
            this.bufferPool = new MappedByteBufferPool();
    }

    public void addFrameHandlerFactory(FrameHandlerFactory frameHandlerFactory)
    {
        synchronized (this)
        {
            this.factories.add(frameHandlerFactory);
        }
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public ClassLoader getContextClassloader()
    {
        return context.getClassLoader();
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this.extensionRegistry;
    }

    public List<FrameHandlerFactory> getFrameHandlerFactories()
    {
        synchronized (this)
        {
            return factories;
        }
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    /**
     * Get the base policy in use for WebSockets.
     *
     * @return the base policy
     */
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public FrameHandler newFrameHandler(Object websocketPojo, WebSocketPolicy policy, HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse)
    {
        Objects.requireNonNull(websocketPojo, "WebSocket Class cannot be null");

        FrameHandler frameHandler = null;

        if (getFrameHandlerFactories().isEmpty())
        {
            LOG.warn("There are no {} instances registered", FrameHandlerFactory.class);
            return null;
        }

        for (FrameHandlerFactory factory : getFrameHandlerFactories())
        {
            frameHandler = factory.newFrameHandler(websocketPojo, policy, handshakeRequest, handshakeResponse);
            if (frameHandler != null)
                return frameHandler;
        }

        // No factory worked!
        return frameHandler;
    }
}
