//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.websocket.api.Configurable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.server.internal.ServerFrameHandlerFactory;
import org.eclipse.jetty.websocket.server.internal.ServerUpgradeRequestDelegate;
import org.eclipse.jetty.websocket.server.internal.ServerUpgradeResponseDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A server-side WebSocket container that allows to {@link #addMapping(String, WebSocketCreator) map}
 * URI paths to WebSocket endpoints and configure WebSocket parameters such as idle timeouts,
 * max WebSocket message sizes, etc.</p>
 */
public class ServerWebSocketContainer extends ContainerLifeCycle implements WebSocketContainer, Configurable, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerWebSocketContainer.class);

    private final List<WebSocketSessionListener> listeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();
    private final Configuration configuration = new Configuration();
    private final WebSocketMappings mappings;
    private final FrameHandlerFactory factory;
    private InvocationType invocationType = InvocationType.BLOCKING;

    public ServerWebSocketContainer(WebSocketMappings mappings)
    {
        this.mappings = mappings;
        this.factory = new ServerFrameHandlerFactory(this, mappings.getWebSocketComponents());
        addSessionListener(sessionTracker);
        addBean(sessionTracker);
    }

    public WebSocketComponents getWebSocketComponents()
    {
        return mappings.getWebSocketComponents();
    }

    @Override
    public Executor getExecutor()
    {
        return getWebSocketComponents().getExecutor();
    }

    @Override
    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        listeners.add(listener);
    }

    @Override
    public boolean removeSessionListener(WebSocketSessionListener listener)
    {
        return listeners.remove(listener);
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> consumer)
    {
        for (WebSocketSessionListener listener : listeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failure while invoking listener {}", listener, x);
            }
        }
    }

    @Override
    public Duration getIdleTimeout()
    {
        return configuration.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        configuration.setIdleTimeout(duration);
    }

    @Override
    public int getInputBufferSize()
    {
        return configuration.getInputBufferSize();
    }

    @Override
    public void setInputBufferSize(int size)
    {
        configuration.setInputBufferSize(size);
    }

    @Override
    public int getOutputBufferSize()
    {
        return configuration.getOutputBufferSize();
    }

    @Override
    public void setOutputBufferSize(int size)
    {
        configuration.setOutputBufferSize(size);
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return configuration.getMaxBinaryMessageSize();
    }

    @Override
    public void setMaxBinaryMessageSize(long size)
    {
        configuration.setMaxBinaryMessageSize(size);
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return configuration.getMaxTextMessageSize();
    }

    @Override
    public void setMaxTextMessageSize(long size)
    {
        configuration.setMaxTextMessageSize(size);
    }

    @Override
    public long getMaxFrameSize()
    {
        return configuration.getMaxFrameSize();
    }

    @Override
    public void setMaxFrameSize(long maxFrameSize)
    {
        configuration.setMaxFrameSize(maxFrameSize);
    }

    @Override
    public boolean isAutoFragment()
    {
        return configuration.isAutoFragment();
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {
        configuration.setAutoFragment(autoFragment);
    }

    @Override
    public int getMaxOutgoingFrames()
    {
        return configuration.getMaxOutgoingFrames();
    }

    @Override
    public void setMaxOutgoingFrames(int maxOutgoingFrames)
    {
        configuration.setMaxOutgoingFrames(maxOutgoingFrames);
    }

    /**
     * <p>Maps the given {@code pathSpec} to the creator of WebSocket endpoints.</p>
     * <p>The {@code pathSpec} format is that supported by
     * {@link WebSocketMappings#parsePathSpec(String)}.</p>
     *
     * @param pathSpec the {@code pathSpec} to associate to the creator
     * @param creator the creator of WebSocket endpoints
     */
    public void addMapping(String pathSpec, WebSocketCreator creator)
    {
        addMapping(WebSocketMappings.parsePathSpec(pathSpec), creator);
    }

    /**
     * <p>Maps the given {@code pathSpec} to the creator of WebSocket endpoints.</p>
     *
     * @param pathSpec the {@code pathSpec} to associate to the creator
     * @param creator the creator of WebSocket endpoints
     */
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
    {
        if (mappings.getWebSocketNegotiator(pathSpec) != null)
            throw new WebSocketException("Duplicate WebSocket Mapping for PathSpec " + pathSpec);

        org.eclipse.jetty.websocket.core.server.WebSocketCreator coreCreator = (request, response, callback) ->
        {
            try
            {
                Object webSocket = creator.createWebSocket(new ServerUpgradeRequestDelegate(request), new ServerUpgradeResponseDelegate(request, response), callback);
                if (webSocket == null)
                    callback.succeeded();
                return webSocket;
            }
            catch (Throwable x)
            {
                callback.failed(x);
                return null;
            }
        };
        mappings.addMapping(pathSpec, coreCreator, factory, configuration);
    }

    public boolean handle(Request request, Response response, Callback callback)
    {
        String target = Request.getPathInContext(request);
        WebSocketNegotiator negotiator = mappings.getMatchedNegotiator(target, pathSpec ->
        {
            // Store PathSpec resource mapping as request attribute,
            // for WebSocketCreator implementors to use later if they wish.
            request.setAttribute(PathSpec.class.getName(), pathSpec);
        });

        if (negotiator == null)
            return false;

        try
        {
            return mappings.upgrade(negotiator, request, response, callback, configuration);
        }
        catch (Throwable x)
        {
            callback.failed(x);
            return true;
        }
    }

    /**
     * @return the invocation type, typically blocking or non-blocking, of this container
     * @see #setInvocationType(InvocationType)
     */
    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    /**
     * <p>Sets the invocation type of this container.</p>
     * <p>The invocation type may be set to {@link InvocationType#NON_BLOCKING} when
     * it is known that application code in the listener methods or annotated methods
     * of the WebSocket endpoint does not use blocking APIs.</p>
     * <p>Setting the invocation type to {@link InvocationType#NON_BLOCKING}, but then
     * using blocking APIs in the WebSocket endpoint may result in a server lockup.</p>
     * <p>By default {@link InvocationType#BLOCKING} is returned, assuming that
     * application code in the WebSocket endpoint uses blocking APIs.</p>
     *
     * @param invocationType the invocation type of this container
     */
    public void setInvocationType(InvocationType invocationType)
    {
        this.invocationType = invocationType;
    }

    private static class Configuration extends org.eclipse.jetty.websocket.core.Configuration.ConfigurationCustomizer implements Configurable
    {
    }
}
