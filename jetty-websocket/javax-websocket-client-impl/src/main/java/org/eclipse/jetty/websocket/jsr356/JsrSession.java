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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.websocket.CloseReason;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint.Async;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerWrapper;

public class JsrSession extends WebSocketSession implements javax.websocket.Session
{
    private final ClientContainer container;
    private final String id;
    private List<Extension> negotiatedExtensions;
    private Map<String, List<String>> jsrParameterMap;
    private Map<String, String> pathParameters = new HashMap<>();
    private Map<String, Object> userProperties;
    private Decoders decoders;
    private MessageHandlers messageHandlers;
    private JsrAsyncRemote asyncRemote;
    private JsrBasicRemote basicRemote;

    public JsrSession(URI requestURI, EventDriver websocket, LogicalConnection connection, ClientContainer container, String id)
    {
        super(requestURI,websocket,connection);
        this.container = container;
        this.id = id;
    }

    @Override
    public void addMessageHandler(MessageHandler listener) throws IllegalStateException
    {
        this.messageHandlers.add(listener);
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
            asyncRemote = new JsrAsyncRemote(getRemote());
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

    public Decoders getDecodersFacade()
    {
        return this.decoders;
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

    public MessageHandlers getMessageHandlerFacade()
    {
        return messageHandlers;
    }

    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        return messageHandlers.getUnmodifiableHandlerSet();
    }

    public MessageHandlerWrapper getMessageHandlerWrapper(MessageType msgType)
    {
        return messageHandlers.getWrapper(msgType);
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
        return userProperties;
    }

    @Override
    public void removeMessageHandler(MessageHandler handler)
    {
        messageHandlers.remove(handler);
    }

    public void setDecodersFacade(Decoders decoders)
    {
        this.decoders = decoders;
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

    public void setMessageHandlerFacade(MessageHandlers messageHandlers)
    {
        this.messageHandlers = messageHandlers;
    }
}
