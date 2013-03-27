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

public class JsrSession implements Session
{
    private final JettyWebSocketContainer container;
    /** Jetty API Session Impl */
    private final org.eclipse.jetty.websocket.api.Session jettySession;
    private final String id;
    private List<Extension> negotiatedExtensions;
    private Map<String, List<String>> jsrParameterMap;
    private JsrAsyncRemote asyncRemote;
    private JsrBasicRemote basicRemote;

    public JsrSession(JettyWebSocketContainer container, org.eclipse.jetty.websocket.api.Session session, String id)
    {
        this.container = container;
        this.jettySession = session;
        this.id = id;
    }

    @Override
    public void addMessageHandler(MessageHandler listener) throws IllegalStateException
    {
        // TODO Auto-generated method stub
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
            basicRemote = new JsrBasicRemote(jettySession.getRemote());
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
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxTextMessageBufferSize()
    {
        return jettySession.getPolicy().getMaxTextMessageSize();
    }

    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        // TODO Auto-generated method stub
        return null;
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
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<String, String> getPathParameters()
    {
        // TODO Auto-generated method stub
        return null;
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

    public long getTimeout()
    {
        return jettySession.getIdleTimeout();
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
        // TODO Auto-generated method stub
        return null;
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
        // TODO Auto-generated method stub

    }

    @Override
    public void setMaxBinaryMessageBufferSize(int length)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setMaxTextMessageBufferSize(int length)
    {
        // TODO Auto-generated method stub

    }

    public void setTimeout(long milliseconds)
    {
        jettySession.setIdleTimeout(milliseconds);
    }
}
