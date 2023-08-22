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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
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
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.server.internal.ServerFrameHandlerFactory;
import org.eclipse.jetty.websocket.server.internal.ServerUpgradeRequestDelegate;
import org.eclipse.jetty.websocket.server.internal.ServerUpgradeResponseDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A server-side WebSocket container that allows to {@link #addMapping(String, WebSocketCreator) map}
 * URI paths to WebSocket endpoints and configure WebSocket parameters such as idle timeouts,
 * max WebSocket message sizes, etc.</p>
 * <p>Direct WebSocket upgrades not mapped to URI paths are possible via
 * {@link #upgrade(WebSocketCreator, Request, Response, Callback)}.</p>
 */
public class ServerWebSocketContainer extends ContainerLifeCycle implements WebSocketContainer, Configurable, Invocable, Request.Handler
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerWebSocketContainer.class);

    /**
     * <p>Returns the {@link ServerWebSocketContainer}, ensuring that
     * it is available via {@link #from(Context)}.</p>
     * <p>If the {@link ServerWebSocketContainer} is not already available,
     * an instance is created, stored to be available via {@link #from(Context)}
     * and returned.</p>
     * <p>This method should be invoked during the setup of the
     * {@link Handler} hierarchy.</p>
     *
     * @param server the {@link Server} object used to lookup common WebSocket components
     * @param contextHandler the {@link ContextHandler} used to store the {@link ServerWebSocketContainer}
     * @return a non-{@code null} {@link ServerWebSocketContainer}
     */
    public static ServerWebSocketContainer ensure(Server server, ContextHandler contextHandler)
    {
        Context context = contextHandler.getContext();
        ServerWebSocketContainer container = from(context);
        if (container == null)
        {
            WebSocketComponents components = WebSocketServerComponents.ensureWebSocketComponents(server, contextHandler);
            WebSocketMappings mappings = new WebSocketMappings(components);
            container = new ServerWebSocketContainer(mappings);
            context.setAttribute(WebSocketContainer.class.getName(), container);
        }
        return container;
    }

    /**
     * <p>Returns the {@link ServerWebSocketContainer} present as the context attribute
     * under the name corresponding to the full qualified name of class
     * {@link WebSocketContainer}.</p>
     *
     * @param context the {@link Context} to look for the attribute
     * @return the {@link ServerWebSocketContainer} stored as an attribute,
     * or {@code null} if no such attribute is present
     */
    public static ServerWebSocketContainer from(Context context)
    {
        return (ServerWebSocketContainer)context.getAttribute(WebSocketContainer.class.getName());
    }

    private final List<WebSocketSessionListener> listeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();
    private final Configuration configuration = new Configuration();
    private final WebSocketMappings mappings;
    private final FrameHandlerFactory factory;
    private InvocationType invocationType = InvocationType.BLOCKING;

    ServerWebSocketContainer(WebSocketMappings mappings)
    {
        this.mappings = mappings;
        this.factory = new ServerFrameHandlerFactory(this, mappings.getWebSocketComponents());
        addSessionListener(sessionTracker);
        addBean(sessionTracker);
    }

    @Override
    public Executor getExecutor()
    {
        return mappings.getWebSocketComponents().getExecutor();
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

        var coreCreator = newWebSocketCreator(creator);
        mappings.addMapping(pathSpec, coreCreator, factory, configuration);
    }

    /**
     * <p>Matches the given {@code request} against existing WebSocket mappings,
     * upgrading to WebSocket if there is a match.</p>
     * <p>Direct upgrades without using WebSocket mappings may be performed via
     * {@link #upgrade(WebSocketCreator, Request, Response, Callback)}.</p>
     * <p>When {@code true} is returned, a response has been sent to the client
     * and the {@code callback} has been completed; either because of a successful
     * WebSocket upgrade, or because an error has occurred.</p>
     * <p>When {@code false} is returned, a response has not been sent to the
     * client, and the {@code callback} has not been completed; typically because
     * the request path does not match any existing WebSocket mappings, so that
     * the request can be handled by other {@link Handler}s.</p>
     *
     * @param request the request to handle, possibly a WebSocket upgrade request
     * @param response the response to handle
     * @param callback the callback to complete when the handling is complete
     * @return {@code true} in case of WebSocket upgrades or failures,
     * {@code false} if the request was not handled
     * @see #addMapping(PathSpec, WebSocketCreator)
     * @see #upgrade(WebSocketCreator, Request, Response, Callback)
     */
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws IOException
    {
        return mappings.upgrade(request, response, callback, configuration);
    }

    /**
     * <p>Upgrades the given {@code request} without matching against the WebSocket mappings.</p>
     * <p>When {@code true} is returned, a response has been sent to the client
     * and the {@code callback} has been completed; either because of a successful
     * WebSocket upgrade, or because an error has occurred.</p>
     * <p>When {@code false} is returned, a response has not been sent to the
     * client, and the {@code callback} has not been completed; for example because
     * the request is not a WebSocket upgrade; in this case the caller must arrange
     * to send a response and complete the callback.</p>
     *
     * @param creator the creator of the WebSocket endpoint
     * @param request the request to upgrade, possibly a WebSocket upgrade request
     * @param response the response
     * @param callback the callback to complete when the upgrade is complete
     * @return {@code true} in case of WebSocket upgrades or failures,
     * {@code false} if the request was not upgraded
     * @see #handle(Request, Response, Callback)
     */
    public boolean upgrade(WebSocketCreator creator, Request request, Response response, Callback callback)
    {
        try
        {
            var coreCreator = newWebSocketCreator(creator);
            WebSocketNegotiator negotiator = WebSocketNegotiator.from(coreCreator, factory);
            return mappings.upgrade(negotiator, request, response, callback, configuration);
        }
        catch (Throwable x)
        {
            Response.writeError(request, response, callback, x);
            return true;
        }
    }

    private org.eclipse.jetty.websocket.core.server.WebSocketCreator newWebSocketCreator(WebSocketCreator creator)
    {
        return (rq, rs, cb) ->
        {
            try
            {
                Object webSocket = creator.createWebSocket(new ServerUpgradeRequestDelegate(rq), new ServerUpgradeResponseDelegate(rq, rs), cb);
                if (webSocket == null)
                    cb.succeeded();
                return webSocket;
            }
            catch (Throwable x)
            {
                cb.failed(x);
                return null;
            }
        };
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
