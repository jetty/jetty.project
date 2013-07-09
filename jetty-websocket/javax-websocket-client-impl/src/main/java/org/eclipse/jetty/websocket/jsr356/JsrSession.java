//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.jsr356.endpoints.AbstractJsrEventDriver;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.MessageHandlerMetadata;

/**
 * Session for the JSR.
 */
public class JsrSession extends WebSocketSession implements javax.websocket.Session, Configurable
{
    private static final Logger LOG = Log.getLogger(JsrSession.class);
    private final ClientContainer container;
    private final String id;
    private final EndpointConfig config;
    private final EndpointMetadata metadata;
    private final DecoderFactory decoderFactory;
    private final EncoderFactory encoderFactory;
    /** Factory for MessageHandlers */
    private final MessageHandlerFactory messageHandlerFactory;
    /** Array of MessageHandlerWrappers, indexed by {@link MessageType#ordinal()} */
    private final MessageHandlerWrapper wrappers[];
    private Set<MessageHandler> messageHandlerSet;
    private List<Extension> negotiatedExtensions;
    private Map<String, List<String>> jsrParameterMap;
    private Map<String, String> pathParameters = new HashMap<>();
    private JsrAsyncRemote asyncRemote;
    private JsrBasicRemote basicRemote;

    public JsrSession(URI requestURI, EventDriver websocket, LogicalConnection connection, ClientContainer container, String id)
    {
        super(requestURI,websocket,connection);
        if (!(websocket instanceof AbstractJsrEventDriver))
        {
            throw new IllegalArgumentException("Cannot non JSR WebSocket: " + websocket);
        }
        AbstractJsrEventDriver jsr = (AbstractJsrEventDriver)websocket;
        this.config = jsr.getConfig();
        this.metadata = jsr.getMetadata();
        this.container = container;
        this.id = id;
        this.decoderFactory = new DecoderFactory(metadata.getDecoders(),container.getDecoderFactory());
        this.encoderFactory = new EncoderFactory(metadata.getEncoders(),container.getEncoderFactory());
        this.messageHandlerFactory = new MessageHandlerFactory();
        this.wrappers = new MessageHandlerWrapper[MessageType.values().length];
        this.messageHandlerSet = new HashSet<>();
    }

    @Override
    public void addMessageHandler(MessageHandler handler) throws IllegalStateException
    {
        Objects.requireNonNull(handler,"MessageHandler cannot be null");

        synchronized (wrappers)
        {
            for (MessageHandlerMetadata metadata : messageHandlerFactory.getMetadata(handler.getClass()))
            {
                DecoderFactory.Wrapper wrapper = decoderFactory.getWrapperFor(metadata.getMessageClass());
                if (wrapper == null)
                {
                    StringBuilder err = new StringBuilder();
                    err.append("Unable to find decoder for type <");
                    err.append(metadata.getMessageClass().getName());
                    err.append("> used in <");
                    err.append(metadata.getHandlerClass().getName());
                    err.append(">");
                    throw new IllegalStateException(err.toString());
                }

                MessageType key = wrapper.getMetadata().getMessageType();
                MessageHandlerWrapper other = wrappers[key.ordinal()];
                if (other != null)
                {
                    StringBuilder err = new StringBuilder();
                    err.append("Encountered duplicate MessageHandler handling message type <");
                    err.append(key.name());
                    err.append(">, ").append(metadata.getHandlerClass().getName());
                    err.append("<");
                    err.append(metadata.getMessageClass().getName());
                    err.append("> and ");
                    err.append(other.getMetadata().getHandlerClass().getName());
                    err.append("<");
                    err.append(other.getMetadata().getMessageClass().getName());
                    err.append("> both implement this message type");
                    throw new IllegalStateException(err.toString());
                }
                else
                {
                    MessageHandlerWrapper handlerWrapper = new MessageHandlerWrapper(handler,metadata,wrapper);
                    wrappers[key.ordinal()] = handlerWrapper;
                }
            }

            // Update handlerSet
            messageHandlerSet.clear();
            for (MessageHandlerWrapper wrapper : wrappers)
            {
                if (wrapper == null)
                {
                    // skip empty
                    continue;
                }
                messageHandlerSet.add(wrapper.getHandler());
            }
        }
    }

    @Override
    public void close(CloseReason closeReason) throws IOException
    {
        close(closeReason.getCloseCode().getCode(),closeReason.getReasonPhrase());
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

    public DecoderFactory getDecoderFactory()
    {
        return decoderFactory;
    }

    public EncoderFactory getEncoderFactory()
    {
        return encoderFactory;
    }

    public EndpointMetadata getEndpointMetadata()
    {
        return metadata;
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

    public MessageHandlerFactory getMessageHandlerFactory()
    {
        return messageHandlerFactory;
    }

    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        return Collections.unmodifiableSet(messageHandlerSet);
    }

    public MessageHandlerWrapper getMessageHandlerWrapper(MessageType type)
    {
        synchronized (wrappers)
        {
            return wrappers[type.ordinal()];
        }
    }

    @Override
    public List<Extension> getNegotiatedExtensions()
    {
        if (negotiatedExtensions == null)
        {
            negotiatedExtensions = new ArrayList<Extension>();
            for (ExtensionConfig cfg : getUpgradeResponse().getExtensions())
            {
                negotiatedExtensions.add(new JsrExtension(cfg));
            }
        }
        return negotiatedExtensions;
    }

    @Override
    public String getNegotiatedSubprotocol()
    {
        return getUpgradeResponse().getAcceptedSubProtocol();
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
    public String getProtocolVersion()
    {
        return getProtocolVersion();
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
    public URI getRequestURI()
    {
        return getUpgradeRequest().getRequestURI();
    }

    @Override
    public Principal getUserPrincipal()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return config.getUserProperties();
    }

    @Override
    public void init(EndpointConfig config)
    {
        // Initialize encoders
        encoderFactory.init(config);
        // Initialize decoders
        decoderFactory.init(config);
    }

    @Override
    public void removeMessageHandler(MessageHandler handler)
    {
        try
        {
            for (MessageHandlerMetadata metadata : messageHandlerFactory.getMetadata(handler.getClass()))
            {
                DecoderMetadata decoder = decoderFactory.getMetadataFor(metadata.getMessageClass());
                MessageType key = decoder.getMessageType();
                wrappers[key.ordinal()] = null;
            }
        }
        catch (IllegalStateException e)
        {
            LOG.warn("Unable to identify MessageHandler: " + handler.getClass().getName(),e);
        }
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int length)
    {
        getPolicy().setMaxBinaryMessageBufferSize(length);
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        getPolicy().setIdleTimeout(milliseconds);
    }

    @Override
    public void setMaxTextMessageBufferSize(int length)
    {
        getPolicy().setMaxTextMessageBufferSize(length);
    }
}
