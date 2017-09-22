//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.io.JavaxWebSocketConnection;

/**
 * Client Session for the JSR.
 */
public class JavaxWebSocketSession<
        P extends JavaxWebSocketContainer,
        C extends JavaxWebSocketConnection,
        L extends JavaxWebSocketLocalEndpoint,
        R extends JavaxWebSocketRemoteEndpoint>
        extends WebSocketCoreSession<P,C,L,R> implements javax.websocket.Session
{
    private static final Logger LOG = Log.getLogger(JavaxWebSocketSession.class);

    protected final SharedBlockingCallback blocking = new SharedBlockingCallback();

    private EndpointConfig config;
    private AvailableDecoders availableDecoders;
    private AvailableEncoders availableEncoders;

    private Set<MessageHandler> messageHandlerSet;

    private List<Extension> negotiatedExtensions;
    private Map<String, String> pathParameters = new HashMap<>();
    private JavaxWebSocketAsyncRemote asyncRemote;
    private JavaxWebSocketBasicRemote basicRemote;
    /**
     * Optional Future to trigger when the session is opened (or fails to open).
     * Most commonly used from client implementations that want a future to
     * base connect + open success against (like JSR-356 client)
     */
    private CompletableFuture<JavaxWebSocketSession<P,C,L,R>> openFuture;

    public JavaxWebSocketSession(P container, C connection)
    {
        super(container, connection);
        connection.setSession(this);
    }

    public void setOpenFuture(CompletableFuture<JavaxWebSocketSession<P,C,L,R>> future)
    {
        this.openFuture = future;
    }

    @Override
    protected void notifyError(Throwable cause)
    {
        if (openFuture != null && !openFuture.isDone())
            openFuture.completeExceptionally(cause);

        super.notifyError(cause);
    }

    @Override
    protected void notifyOpen()
    {
        if(openFuture != null && !openFuture.isDone())
            openFuture.complete(this);

        super.notifyOpen();
    }

    @Override
    public void setWebSocketEndpoint(Object websocket, WebSocketPolicy policy, L localEndpoint, R remoteEndpoint)
    {
        final Object endpoint;

        if (websocket instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint configuredEndpoint = (ConfiguredEndpoint) websocket;
            endpoint = configuredEndpoint.getRawEndpoint();
            this.config = configuredEndpoint.getConfig();
        }
        else
        {
            endpoint = websocket;
            this.config = new BasicEndpointConfig();
        }

        this.availableDecoders = new AvailableDecoders(this.config);
        this.availableEncoders = new AvailableEncoders(this.config);

        if (this.config instanceof PathParamProvider)
        {
            PathParamProvider pathParamProvider = (PathParamProvider) this.config;
            pathParameters.putAll(pathParamProvider.getPathParams());
        }

        super.setWebSocketEndpoint(endpoint, policy, localEndpoint, remoteEndpoint);
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

    protected synchronized void registerMessageHandler(MessageHandler handler)
    {
        if (messageHandlerSet == null)
        {
            messageHandlerSet = new HashSet<>();
        }
        messageHandlerSet.add(handler);
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
            super.close(new CloseStatus(), blocker);
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
            super.close(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase(), blocker);
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
            asyncRemote = new JavaxWebSocketAsyncRemote(this);
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
            basicRemote = new JavaxWebSocketBasicRemote(this);
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
        return getParentContainer();
    }

    public AvailableDecoders getDecoders()
    {
        return availableDecoders;
    }

    public AvailableEncoders getEncoders()
    {
        return availableEncoders;
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
        return getConnection().getId();
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
     * {@inheritDoc}
     *
     * @see Session#getMaxIdleTimeout()
     * @since JSR356 v1.0
     */
    @Override
    public long getMaxIdleTimeout()
    {
        return getPolicy().getIdleTimeout();
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
        List<ExtensionConfig> extensions = getConnection().getUpgradeResponse().getExtensions();

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
        String acceptedSubProtocol = getConnection().getUpgradeResponse().getAcceptedSubProtocol();
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
        // TODO: maintain internal Set of open sessions
        Set<Session> sessions = new HashSet<>();
        sessions.addAll(getBeans(JavaxWebSocketSession.class));
        return sessions;
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
        return Collections.unmodifiableMap(pathParameters);
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
        return getConnection().getUpgradeRequest().getProtocolVersion();
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
        return getConnection().getUpgradeRequest().getRequestURI().getQuery();
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
        return getConnection().getUpgradeRequest().getParameterMap();
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
        return getConnection().getUpgradeRequest().getRequestURI();
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
        UpgradeRequest request = getConnection().getUpgradeRequest();
        try
        {
            Method method = request.getClass().getMethod("getUserPrincipal");
            return (Principal) method.invoke(request);
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
        return config.getUserProperties();
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
        return getConnection().isSecure();
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
        return getConnection().isOpen();
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
     * @see Session#setMaxIdleTimeout(long)
     * @since JSR356 v1.0
     */
    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        getPolicy().setIdleTimeout(milliseconds);
        super.getConnection().setMaxIdleTimeout(milliseconds);
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

    public void setPathParameters(Map<String, String> pathParams)
    {
        this.pathParameters.clear();
        if (pathParams != null)
        {
            this.pathParameters.putAll(pathParams);
        }
    }

    public BatchMode getBatchMode()
    {
        // JSR 356 specification mandates default batch mode to be off.
        return BatchMode.OFF;
    }
}
