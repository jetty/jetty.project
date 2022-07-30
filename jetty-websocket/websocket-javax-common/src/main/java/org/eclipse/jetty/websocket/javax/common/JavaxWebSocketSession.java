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

package org.eclipse.jetty.websocket.javax.common;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint.Async;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.encoders.AvailableEncoders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client Session for the JSR.
 */
public class JavaxWebSocketSession implements javax.websocket.Session
{
    private static final Logger LOG = LoggerFactory.getLogger(JavaxWebSocketSession.class);

    private final JavaxWebSocketContainer container;
    private final CoreSession coreSession;
    private final JavaxWebSocketFrameHandler frameHandler;
    private final AvailableDecoders availableDecoders;
    private final AvailableEncoders availableEncoders;
    private final Map<String, String> pathParameters;
    private final String sessionId;
    private final Map<String, Object> userProperties;

    private List<Extension> negotiatedExtensions;
    private JavaxWebSocketAsyncRemote asyncRemote;
    private JavaxWebSocketBasicRemote basicRemote;

    public JavaxWebSocketSession(JavaxWebSocketContainer container,
                                 CoreSession coreSession,
                                 JavaxWebSocketFrameHandler frameHandler,
                                 EndpointConfig endpointConfig)
    {
        Objects.requireNonNull(endpointConfig);
        this.container = container;
        this.coreSession = coreSession;
        this.frameHandler = frameHandler;
        this.sessionId = UUID.randomUUID().toString();
        this.availableDecoders = new AvailableDecoders(endpointConfig, container.getWebSocketComponents());
        this.availableEncoders = new AvailableEncoders(endpointConfig, container.getWebSocketComponents());

        if (endpointConfig instanceof PathParamProvider)
        {
            PathParamProvider pathParamProvider = (PathParamProvider)endpointConfig;
            this.pathParameters = new HashMap<>(pathParamProvider.getPathParams());
        }
        else
        {
            this.pathParameters = Collections.emptyMap();
        }

        this.userProperties = endpointConfig.getUserProperties();
        container.notifySessionListeners((listener) -> listener.onJavaxWebSocketSessionCreated(this));
    }

    public CoreSession getCoreSession()
    {
        return coreSession;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#addMessageHandler(Class, MessageHandler.Partial)
     * @since JSR356 v1.1
     */
    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler)
    {
        Objects.requireNonNull(handler, "MessageHandler.Partial cannot be null");
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Add MessageHandler.Partial: {}", handler);
        }

        frameHandler.addMessageHandler(clazz, handler);
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#addMessageHandler(Class, MessageHandler.Whole)
     * @since JSR356 v1.1
     */
    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler)
    {
        Objects.requireNonNull(handler, "MessageHandler.Whole cannot be null");
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Add MessageHandler.Whole: {}", handler);
        }

        frameHandler.addMessageHandler(clazz, handler);
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#addMessageHandler(MessageHandler)
     * @since JSR356 v1.0
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void addMessageHandler(MessageHandler handler) throws IllegalStateException
    {
        Objects.requireNonNull(handler, "MessageHandler cannot be null");
        Class<? extends MessageHandler> handlerClass = handler.getClass();
        boolean added = false;

        if (MessageHandler.Whole.class.isAssignableFrom(handlerClass))
        {
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handlerClass, MessageHandler.Whole.class);
            addMessageHandler(onMessageClass, (MessageHandler.Whole)handler);
            added = true;
        }

        if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
        {
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handlerClass, MessageHandler.Partial.class);
            addMessageHandler(onMessageClass, (MessageHandler.Partial)handler);
            added = true;
        }

        if (!added)
        {
            // Should not be possible
            throw new IllegalStateException("Not a recognized " + MessageHandler.class.getName() + " type: " + handler.getClass());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#close()
     * @since JSR356 v1.0
     */
    @Override
    public void close()
    {
        close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, null));
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#close(CloseReason)
     * @since JSR356 v1.0
     */
    @Override
    public void close(CloseReason closeReason)
    {
        try
        {
            coreSession.close(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase(), Callback.NOOP);
        }
        catch (Throwable t)
        {
            LOG.trace("IGNORED", t);
        }
    }

    private long getBlockingTimeout()
    {
        long idleTimeout = getMaxIdleTimeout();
        return (idleTimeout > 0) ? idleTimeout + 1000 : idleTimeout;
    }

    /**
     * Access for MethodHandle implementations to filter the return value of user provided TEXT/BINARY
     * based message handling methods.
     *
     * @param obj the return object
     */
    @SuppressWarnings("unused") // used by JavaxWebSocketFrameHandlerFactory via MethodHandle
    public void filterReturnType(Object obj)
    {
        if (obj != null)
        {
            try
            {
                getBasicRemote().sendObject(obj);
            }
            catch (Exception cause)
            {
                // TODO review this
                throw new RuntimeException(cause);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getAsyncRemote()
     * @since JSR356 v1.0
     */
    @Override
    public Async getAsyncRemote()
    {
        if (asyncRemote == null)
        {
            asyncRemote = new JavaxWebSocketAsyncRemote(this, coreSession);
        }
        return asyncRemote;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getBasicRemote()
     * @since JSR356 v1.0
     */
    @Override
    public Basic getBasicRemote()
    {
        if (basicRemote == null)
        {
            basicRemote = new JavaxWebSocketBasicRemote(this, coreSession);
        }
        return basicRemote;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getContainer()
     * @since JSR356 v1.0
     */
    @Override
    public WebSocketContainer getContainer()
    {
        return this.container;
    }

    public JavaxWebSocketContainer getContainerImpl()
    {
        return this.container;
    }

    public AvailableDecoders getDecoders()
    {
        return availableDecoders;
    }

    public AvailableEncoders getEncoders()
    {
        return availableEncoders;
    }

    public Object getEndpoint()
    {
        return frameHandler.getEndpoint();
    }

    public JavaxWebSocketFrameHandler getFrameHandler()
    {
        return frameHandler;
    }

    public void abort()
    {
        coreSession.abort();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getId()
     * @since JSR356 v1.0
     */
    @Override
    public String getId()
    {
        return sessionId;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getMaxBinaryMessageBufferSize()
     * @since JSR356 v1.0
     */
    @Override
    public int getMaxBinaryMessageBufferSize()
    {
        long maxBinaryMsgSize = coreSession.getMaxBinaryMessageSize();
        return (maxBinaryMsgSize > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)maxBinaryMsgSize;
    }

    /**
     * Maximum size of a whole BINARY message that this implementation can buffer.
     *
     * @param length the length in bytes
     * @see Session#setMaxBinaryMessageBufferSize(int)
     * @since JSR356 v1.0
     */
    @Override
    public void setMaxBinaryMessageBufferSize(int length)
    {
        coreSession.setMaxBinaryMessageSize(length);
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getMaxIdleTimeout()
     * @since JSR356 v1.0
     */
    @Override
    public long getMaxIdleTimeout()
    {
        return coreSession.getIdleTimeout().toMillis();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#setMaxIdleTimeout(long)
     * @since JSR356 v1.0
     */
    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        coreSession.setIdleTimeout(Duration.ofMillis(milliseconds));
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getMaxTextMessageBufferSize()
     * @since JSR356 v1.0
     */
    @Override
    public int getMaxTextMessageBufferSize()
    {
        long maxTextMsgSize = coreSession.getMaxTextMessageSize();
        return (maxTextMsgSize > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)maxTextMsgSize;
    }

    /**
     * Maximum size of a whole TEXT message that this implementation can buffer.
     *
     * @param length the length in bytes
     * @see Session#setMaxTextMessageBufferSize(int)
     * @since JSR356 v1.0
     */
    @Override
    public void setMaxTextMessageBufferSize(int length)
    {
        coreSession.setMaxTextMessageSize(length);
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getMessageHandlers()
     * @since JSR356 v1.0
     */
    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        return frameHandler.getMessageHandlers();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getNegotiatedExtensions()
     * @since JSR356 v1.0
     */
    @Override
    public List<Extension> getNegotiatedExtensions()
    {
        List<ExtensionConfig> extensions = coreSession.getNegotiatedExtensions();

        if ((negotiatedExtensions == null) && extensions != null)
        {
            negotiatedExtensions = extensions.stream().map(JavaxWebSocketExtension::new).collect(Collectors.toList());
        }
        return negotiatedExtensions;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getNegotiatedSubprotocol()
     * @since JSR356 v1.0
     */
    @Override
    public String getNegotiatedSubprotocol()
    {
        String acceptedSubProtocol = coreSession.getNegotiatedSubProtocol();
        if (acceptedSubProtocol == null)
        {
            return "";
        }
        return acceptedSubProtocol;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getOpenSessions()
     * @since JSR356 v1.0
     */
    @Override
    public Set<Session> getOpenSessions()
    {
        return container.getOpenSessions();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getPathParameters()
     * @since JSR356 v1.0
     */
    @Override
    public Map<String, String> getPathParameters()
    {
        return pathParameters;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getProtocolVersion()
     * @since JSR356 v1.0
     */
    @Override
    public String getProtocolVersion()
    {
        return coreSession.getProtocolVersion();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getQueryString()
     * @since JSR356 v1.0
     */
    @Override
    public String getQueryString()
    {
        return coreSession.getRequestURI().getQuery();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getRequestParameterMap()
     * @since JSR356 v1.0
     */
    @Override
    public Map<String, List<String>> getRequestParameterMap()
    {
        // TODO: calculate static Map in Constructor
        return coreSession.getParameterMap();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getRequestURI() )
     * @since JSR356 v1.0
     */
    @Override
    public URI getRequestURI()
    {
        return coreSession.getRequestURI();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getUserPrincipal()
     * @since JSR356 v1.0
     */
    @Override
    public Principal getUserPrincipal()
    {
        return this.frameHandler.getUpgradeRequest().getUserPrincipal();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#getUserProperties()
     * @since JSR356 v1.0
     */
    @Override
    public Map<String, Object> getUserProperties()
    {
        return this.userProperties;
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#isOpen()
     * @since JSR356 v1.0
     */
    @Override
    public boolean isOpen()
    {
        return coreSession.isOutputOpen();
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#isSecure()
     * @since JSR356 v1.0
     */
    @Override
    public boolean isSecure()
    {
        return coreSession.isSecure();
    }

    @Override
    public void removeMessageHandler(MessageHandler handler)
    {
        frameHandler.removeMessageHandler(handler);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s,%s]", this.getClass().getSimpleName(), this.hashCode(),
            coreSession.getBehavior(), frameHandler);
    }
}
