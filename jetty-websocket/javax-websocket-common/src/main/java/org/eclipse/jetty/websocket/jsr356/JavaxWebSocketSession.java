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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint.Async;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.common.WebSocketContainerContext;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.util.ReflectUtils;

/**
 * Client Session for the JSR.
 */
public class JavaxWebSocketSession extends AbstractLifeCycle implements javax.websocket.Session
{
    private static final Logger LOG = Log.getLogger(JavaxWebSocketSession.class);

    protected final SharedBlockingCallback blocking = new SharedBlockingCallback();
    private final JavaxWebSocketContainer container;
    private final FrameHandler.Channel channel;
    private final HandshakeRequest handshakeRequest;
    private final HandshakeResponse upgradeResponse;
    private final JavaxWebSocketFrameHandler frameHandler;
    private final WebSocketPolicy policy;
    private final EndpointConfig config;
    private final String id;
    private final AvailableDecoders availableDecoders;
    private final AvailableEncoders availableEncoders;
    private final Map<String, String> pathParameters;
    private Map<String,Object> userProperties;

    private Set<MessageHandler> messageHandlerSet;
    private List<Extension> negotiatedExtensions;
    private JavaxWebSocketAsyncRemote asyncRemote;
    private JavaxWebSocketBasicRemote basicRemote;

    public JavaxWebSocketSession(JavaxWebSocketContainer container,
                                 FrameHandler.Channel channel,
                                 JavaxWebSocketFrameHandler frameHandler,
                                 HandshakeRequest handshakeRequest,
                                 HandshakeResponse handshakeResponse,
                                 String id,
                                 EndpointConfig endpointConfig)
    {
        this.container = container;
        this.channel = channel;
        this.frameHandler = frameHandler;
        this.handshakeRequest = handshakeRequest;
        this.upgradeResponse = handshakeResponse;
        this.policy = frameHandler.getPolicy();
        this.id = id;

        this.config = endpointConfig == null ? new BasicEndpointConfig() : endpointConfig;

        this.availableDecoders = new AvailableDecoders(this.config);
        this.availableEncoders = new AvailableEncoders(this.config);

        if (this.config instanceof PathParamProvider)
        {
            PathParamProvider pathParamProvider = (PathParamProvider) this.config;
            this.pathParameters = new HashMap<>(pathParamProvider.getPathParams());
        }
        else
        {
            this.pathParameters = Collections.emptyMap();
        }

        this.userProperties = new HashMap<>(this.config.getUserProperties());
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
            LOG.debug("MessageHandler.Partial class: {}", handler.getClass());
        }

        /*
        TODO: which type? (TEXT or BINARY)
        TODO: which decoder?
        TODO: create message sink
        TODO: localEndpoint.set(Text|Binary)Sink(sink)
        MessageSink partialSink = container.getMessageSink(clazz, handler);
        localEndpoint.set

        getJsrEndpointFunctions().setMessageHandler(clazz, handler);
        */
        registerMessageHandler(handler);
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
            LOG.debug("MessageHandler.Whole class: {}", handler.getClass());
        }

        /*
        TODO: which type? (TEXT, BINARY, or PongMessage)
        TODO: which decoder? (if not PongMessage)
        TODO: create message sink
        TODO: localEndpoint.set(Text|Binary)Sink(sink)
        getJsrEndpointFunctions().setMessageHandler(clazz, handler);
         */
        registerMessageHandler(handler);
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
            addMessageHandler(onMessageClass, (MessageHandler.Whole) handler);
            added = true;
        }

        if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
        {
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handlerClass, MessageHandler.Partial.class);
            addMessageHandler(onMessageClass, (MessageHandler.Partial) handler);
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
    public void close() throws IOException
    {
        try (SharedBlockingCallback.Blocker blocker = blocking.acquire())
        {
            channel.close(blocker);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see Session#close(CloseReason)
     * @since JSR356 v1.0
     */
    @Override
    public void close(CloseReason closeReason) throws IOException
    {
        try (SharedBlockingCallback.Blocker blocker = blocking.acquire())
        {
            channel.close(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase(), blocker);
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
            asyncRemote = new JavaxWebSocketAsyncRemote(this, channel);
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
            basicRemote = new JavaxWebSocketBasicRemote(this, channel);
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

    public WebSocketContainerContext getContainerContext()
    {
        return container;
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

    public EndpointConfig getEndpointConfig()
    {
        return config;
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
        return this.id;
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
        return getPolicy().getMaxBinaryMessageSize();
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
        getPolicy().setMaxBinaryMessageSize(length);
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
        return channel.getIdleTimeout(TimeUnit.MILLISECONDS);
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
        getPolicy().setIdleTimeout(milliseconds);
        channel.setIdleTimeout(milliseconds, TimeUnit.MILLISECONDS);
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
        return getPolicy().getMaxTextMessageSize();
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
        getPolicy().setMaxTextMessageSize(length);
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
        if (messageHandlerSet == null)
        {
            return Collections.emptySet();
        }

        // Always return copy of set, as it is common to iterate and remove from the real set.
        return new HashSet<>(messageHandlerSet);
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
        List<ExtensionConfig> extensions = upgradeResponse.getExtensions();

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
        String acceptedSubProtocol = upgradeResponse.getAcceptedSubProtocol();
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

    public WebSocketPolicy getPolicy()
    {
        return policy;
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
        return handshakeRequest.getProtocolVersion();
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
        return handshakeRequest.getRequestURI().getQuery();
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
        return handshakeRequest.getParameterMap();
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
        return handshakeRequest.getRequestURI();
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
        try
        {
            Method method = handshakeRequest.getClass().getMethod("getUserPrincipal");
            return (Principal) method.invoke(handshakeRequest);
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to access UserPrincipal", t);
        }

        return null;
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
        return channel.isOpen();
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
        return handshakeRequest.isSecure();
    }

    @Override
    public synchronized void removeMessageHandler(MessageHandler handler)
    {
        if (messageHandlerSet != null && messageHandlerSet.remove(handler))
        {
            // remove from endpoint functions too
            /*
            TODO: find associated type (TEXT / BINARY / PongMessage)
            TODO: remove from localEndpoint the appropriate
            getJsrEndpointFunctions().removeMessageHandler(handler);
            */
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s,%s]", this.getClass().getSimpleName(), this.hashCode(),
                getPolicy().getBehavior(), frameHandler);
    }

    protected SharedBlockingCallback getBlocking()
    {
        return blocking;
    }

    protected synchronized void registerMessageHandler(MessageHandler handler)
    {
        if (messageHandlerSet == null)
        {
            messageHandlerSet = new HashSet<>();
        }
        messageHandlerSet.add(handler);
    }
}
