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
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint.Async;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.function.EndpointFunctions;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.function.JsrEndpointFunctions;

/**
 * Client Session for the JSR.
 */
public class JsrSession extends WebSocketSession implements javax.websocket.Session
{
    private static final Logger LOG = Log.getLogger(JsrSession.class);
    private final ClientContainer container;
    private final String id;
    private final EndpointConfig config;
    private AvailableDecoders availableDecoders;
    private AvailableEncoders availableEncoders;
    
    private Set<MessageHandler> messageHandlerSet;
    
    private List<Extension> negotiatedExtensions;
    private Map<String, String> pathParameters = new HashMap<>();
    private JsrAsyncRemote asyncRemote;
    private JsrBasicRemote basicRemote;
    
    public JsrSession(ClientContainer container, String id, URI requestURI, Object websocket, LogicalConnection connection)
    {
        super(container, requestURI, websocket, connection);
        
        this.container = container;
        
        if (websocket instanceof ConfiguredEndpoint)
            this.config = ((ConfiguredEndpoint) websocket).getConfig();
        else
            this.config = new BasicEndpointConfig();
        
        this.availableDecoders = new AvailableDecoders(this.config);
        this.availableEncoders = new AvailableEncoders(this.config);
        
        if(this.config instanceof PathParamProvider)
        {
            PathParamProvider pathParamProvider = (PathParamProvider) this.config;
            pathParameters.putAll(pathParamProvider.getPathParams());
        }
        
        this.id = id;
    }
    
    @Override
    public EndpointFunctions newEndpointFunctions(Object endpoint)
    {
        // Delegate to container to obtain correct version of JsrEndpointFunctions
        // Could be a Client version, or a Server version
        return container.newJsrEndpointFunction(endpoint,
                getPolicy(),
                availableEncoders,
                availableDecoders,
                pathParameters,
                config);
    }
    
    private JsrEndpointFunctions getJsrEndpointFunctions()
    {
        return (JsrEndpointFunctions) endpointFunctions;
    }
    
    /**
     * {@inheritDoc}
     *
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
        
        getJsrEndpointFunctions().setMessageHandler(clazz, handler);
        registerMessageHandler(handler);
    }
    
    /**
     * {@inheritDoc}
     *
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
        getJsrEndpointFunctions().setMessageHandler(clazz, handler);
        registerMessageHandler(handler);
    }
    
    /**
     * {@inheritDoc}
     *
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
    
    @Override
    public void close(CloseReason closeReason) throws IOException
    {
        close(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
    }
    
    @Override
    public Async getAsyncRemote()
    {
        if (asyncRemote == null)
        {
            asyncRemote = new JsrAsyncRemote(this);
        }
        return asyncRemote;
    }
    
    @Override
    public Basic getBasicRemote()
    {
        if (basicRemote == null)
        {
            basicRemote = new JsrBasicRemote(this);
        }
        return basicRemote;
    }
    
    @Override
    public WebSocketContainer getContainer()
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
    
    public EndpointConfig getEndpointConfig()
    {
        return config;
    }
    
    @Override
    public String getId()
    {
        return this.id;
    }
    
    @Override
    public int getMaxBinaryMessageBufferSize()
    {
        return getPolicy().getMaxBinaryMessageSize();
    }
    
    @Override
    public long getMaxIdleTimeout()
    {
        return getPolicy().getIdleTimeout();
    }
    
    @Override
    public int getMaxTextMessageBufferSize()
    {
        return getPolicy().getMaxTextMessageSize();
    }
    
    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        if (messageHandlerSet == null)
        {
            return Collections.emptySet();
        }
        
        // Always return copy of set, as it is common to iterate and remove from the real set.
        return new HashSet<MessageHandler>(messageHandlerSet);
    }
    
    @Override
    public List<Extension> getNegotiatedExtensions()
    {
        if ((negotiatedExtensions == null) && getUpgradeResponse().getExtensions() != null)
        {
            negotiatedExtensions = getUpgradeResponse().getExtensions().stream().map(JsrExtension::new).collect(Collectors.toList());
        }
        return negotiatedExtensions;
    }
    
    @Override
    public String getNegotiatedSubprotocol()
    {
        String acceptedSubProtocol = getUpgradeResponse().getAcceptedSubProtocol();
        if (acceptedSubProtocol == null)
        {
            return "";
        }
        return acceptedSubProtocol;
    }
    
    @Override
    public Set<Session> getOpenSessions()
    {
        return container.getOpenSessions();
    }
    
    @Override
    public Map<String, String> getPathParameters()
    {
        return Collections.unmodifiableMap(pathParameters);
    }
    
    @Override
    public String getQueryString()
    {
        return getUpgradeRequest().getRequestURI().getQuery();
    }
    
    @Override
    public Map<String, List<String>> getRequestParameterMap()
    {
        return getUpgradeRequest().getParameterMap();
    }
    
    @Override
    public Principal getUserPrincipal()
    {
        return getUpgradeRequest().getUserPrincipal();
    }
    
    @Override
    public Map<String, Object> getUserProperties()
    {
        return config.getUserProperties();
    }
    
    @Override
    public synchronized void removeMessageHandler(MessageHandler handler)
    {
        if (messageHandlerSet != null && messageHandlerSet.remove(handler))
        {
            // remove from endpoint functions too
            getJsrEndpointFunctions().removeMessageHandler(handler);
        }
    }

    /**
     * Maximum size of a whole BINARY message that this implementation can buffer.
     *
     * @param length the length in bytes
     */
    @Override
    public void setMaxBinaryMessageBufferSize(int length)
    {
        getPolicy().setMaxBinaryMessageSize(length);
    }
    
    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        getPolicy().setIdleTimeout(milliseconds);
        super.setIdleTimeout(milliseconds);
    }

    /**
     * Maximum size of a whole TEXT message that this implementation can buffer.
     *
     * @param length the length in bytes
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
    
    @Override
    public BatchMode getBatchMode()
    {
        // JSR 356 specification mandates default batch mode to be off.
        return BatchMode.OFF;
    }
}
