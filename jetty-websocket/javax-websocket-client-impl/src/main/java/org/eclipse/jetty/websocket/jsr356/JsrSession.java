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
import org.eclipse.jetty.websocket.common.WebSocketSession;

public class JsrSession implements Session
{
    private final JettyWebSocketContainer container;
    /** Jetty API Session Impl */
    private final WebSocketSession jettySession;
    private final String id;
    private List<Extension> negotiatedExtensions;
    private Map<String, List<String>> jsrParameterMap;
    private Map<String, String> pathParameters = new HashMap<>();
    private Map<String, Object> userProperties;
    private Set<MessageHandler> messageHandlers;
    private JsrAsyncRemote asyncRemote;
    private JsrBasicRemote basicRemote;

    public JsrSession(JettyWebSocketContainer container, WebSocketSession session, String id)
    {
        this.container = container;
        this.jettySession = session;
        this.id = id;
    }

    @Override
    public void addMessageHandler(MessageHandler listener) throws IllegalStateException
    {
        messageHandlers.add(listener);
    }

    @Override
    public void close() throws IOException
    {
        jettySession.close();
    }

    @Override
    public void close(CloseReason closeStatus) throws IOException
    {
        jettySession.close(closeStatus.getCloseCode().getCode(),closeStatus.getReasonPhrase());
    }

    @Override
    public Async getAsyncRemote()
    {
        if (asyncRemote == null)
        {
            asyncRemote = new JsrAsyncRemote(jettySession.getRemote());
        }
        return asyncRemote;
    }

    @Override
    public Basic getBasicRemote()
    {
        if (basicRemote == null)
        {
            basicRemote = new JsrBasicRemote(jettySession);
        }
        return basicRemote;
    }

    @Override
    public WebSocketContainer getContainer()
    {
        return this.container;
    }

    @Override
    public String getId()
    {
        return this.id;
    }

    @Override
    public int getMaxBinaryMessageBufferSize()
    {
        return jettySession.getPolicy().getMaxBinaryMessageSize();
    }

    @Override
    public long getMaxIdleTimeout()
    {
        return jettySession.getPolicy().getIdleTimeout();
    }

    @Override
    public int getMaxTextMessageBufferSize()
    {
        return jettySession.getPolicy().getMaxTextMessageSize();
    }

    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        return messageHandlers;
    }

    @Override
    public List<Extension> getNegotiatedExtensions()
    {
        if (negotiatedExtensions == null)
        {
            negotiatedExtensions = new ArrayList<Extension>();
            for (ExtensionConfig cfg : jettySession.getUpgradeResponse().getExtensions())
            {
                negotiatedExtensions.add(new JsrExtension(cfg));
            }
        }
        return negotiatedExtensions;
    }

    @Override
    public String getNegotiatedSubprotocol()
    {
        return jettySession.getUpgradeResponse().getAcceptedSubProtocol();
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
        return jettySession.getProtocolVersion();
    }

    @Override
    public String getQueryString()
    {
        return jettySession.getUpgradeRequest().getRequestURI().getQuery();
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap()
    {
        return jettySession.getUpgradeRequest().getParameterMap();
    }

    @Override
    public URI getRequestURI()
    {
        return jettySession.getUpgradeRequest().getRequestURI();
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
    public boolean isOpen()
    {
        return jettySession.isOpen();
    }

    @Override
    public boolean isSecure()
    {
        return jettySession.isSecure();
    }

    @Override
    public void removeMessageHandler(MessageHandler handler)
    {
        messageHandlers.remove(handler);
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int length)
    {
        jettySession.getPolicy().setMaxBinaryMessageBufferSize(length);
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        jettySession.getPolicy().setIdleTimeout(milliseconds);
    }

    @Override
    public void setMaxTextMessageBufferSize(int length)
    {
        jettySession.getPolicy().setMaxTextMessageBufferSize(length);
    }
}
