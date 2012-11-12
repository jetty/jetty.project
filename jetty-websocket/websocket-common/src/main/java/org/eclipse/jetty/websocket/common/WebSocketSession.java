//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import javax.net.websocket.ClientContainer;
import javax.net.websocket.CloseReason;
import javax.net.websocket.ContainerProvider;
import javax.net.websocket.Encoder;
import javax.net.websocket.MessageHandler;
import javax.net.websocket.RemoteEndpoint;
import javax.net.websocket.SendResult;
import javax.net.websocket.Session;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.events.EventDriver;

@ManagedObject
public class WebSocketSession extends ContainerLifeCycle implements Session<Object>, WebSocketConnection, IncomingFrames
{
    private static final Logger LOG = Log.getLogger(WebSocketSession.class);
    private final URI requestURI;
    private final EventDriver websocket;
    private final LogicalConnection connection;
    private Set<MessageHandler> messageHandlers = new HashSet<>();
    private List<Encoder> encoders = new ArrayList<>();
    private ExtensionFactory extensionFactory;
    private boolean active = false;
    private long maximumMessageSize;
    private long inactiveTime;
    private List<String> negotiatedExtensions = new ArrayList<>();
    private String protocolVersion;
    private String negotiatedSubprotocol;
    private long timeout;
    private Map<String, String[]> parameterMap = new HashMap<>();
    private List<ExtensionConfig> extensionConfigs = new ArrayList<>();
    private WebSocketRemoteEndpoint remote;
    private IncomingFrames incomingHandler;
    private OutgoingFrames outgoingHandler;
    private WebSocketPolicy policy;

    public WebSocketSession(URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        if (requestURI == null)
        {
            throw new RuntimeException("Request URI cannot be null");
        }

        this.requestURI = requestURI;
        this.websocket = websocket;
        this.connection = connection;
        this.outgoingHandler = connection;
        this.incomingHandler = websocket;

        // Get the parameter map (use the jetty MultiMap to do this right)
        MultiMap<String> params = new MultiMap<>();
        String query = requestURI.getQuery();
        if (StringUtil.isNotBlank(query))
        {
            UrlEncoded.decodeTo(query,params,StringUtil.__UTF8);
        }

        for (String name : params.keySet())
        {
            List<String> valueList = params.getValues(name);
            String valueArr[] = new String[valueList.size()];
            valueArr = valueList.toArray(valueArr);
            parameterMap.put(name,valueArr);
        }
    }

    @Override
    public void addMessageHandler(MessageHandler listener)
    {
        messageHandlers.add(listener);
    }

    @Override
    public void close() throws IOException
    {
        connection.close();
    }

    @Override
    public void close(CloseReason closeStatus) throws IOException
    {
        connection.close(closeStatus.getCloseCode().getCode(),closeStatus.getReasonPhrase());
    }

    @Override
    public void close(int statusCode, String reason)
    {
        connection.close(statusCode,reason);
    }

    public LogicalConnection getConnection()
    {
        return connection;
    }

    @Override
    public ClientContainer getContainer()
    {
        return ContainerProvider.getClientContainer();
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    @Override
    public long getInactiveTime()
    {
        return inactiveTime;
    }

    @ManagedAttribute(readonly = true)
    public IncomingFrames getIncomingHandler()
    {
        return incomingHandler;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return connection.getLocalAddress();
    }

    @Override
    public long getMaximumMessageSize()
    {
        return maximumMessageSize;
    }

    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        return Collections.unmodifiableSet(messageHandlers);
    }

    @Override
    public List<String> getNegotiatedExtensions()
    {
        return negotiatedExtensions;
    }

    @Override
    public String getNegotiatedSubprotocol()
    {
        return negotiatedSubprotocol;
    }

    @ManagedAttribute(readonly = true)
    public OutgoingFrames getOutgoingHandler()
    {
        return outgoingHandler;
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        return parameterMap;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public String getProtocolVersion()
    {
        return protocolVersion;
    }

    @Override
    public String getQueryString()
    {
        return getRequestURI().getQuery();
    }

    @Override
    public RemoteEndpoint<Object> getRemote()
    {
        if (!isOpen())
        {
            throw new WebSocketException("Session has not been opened yet");
        }
        return remote;
    }

    @Override
    public RemoteEndpoint<Object> getRemote(Class<Object> c)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return remote.getInetSocketAddress();
    }

    @Override
    public URI getRequestURI()
    {
        return requestURI;
    }

    @Override
    public String getSubProtocol()
    {
        return getNegotiatedSubprotocol();
    }

    /**
     * The timeout in seconds
     */
    @Override
    public long getTimeout()
    {
        return timeout;
    }

    /**
     * Incoming Errors from Parser
     */
    @Override
    public void incomingError(WebSocketException e)
    {
        if (connection.isInputClosed())
        {
            return; // input is closed
        }
        // Forward Errors to User WebSocket Object
        websocket.incomingError(e);
    }

    /**
     * Incoming Raw Frames from Parser
     */
    @Override
    public void incomingFrame(Frame frame)
    {
        if (connection.isInputClosed())
        {
            return; // input is closed
        }

        // Forward Frames Through Extension List
        incomingHandler.incomingFrame(frame);
    }

    @Override
    public boolean isActive()
    {
        return active;
    }

    @Override
    public boolean isOpen()
    {
        return isActive();
    }

    @Override
    public boolean isSecure()
    {
        return getRequestURI().getScheme().equalsIgnoreCase("wss");
    }

    /**
     * Open/Activate the session
     * 
     * @throws IOException
     */
    public void open()
    {
        if (isOpen())
        {
            throw new WebSocketException("Cannot Open WebSocketSession, Already open");
        }

        // Initialize extensions
        LOG.debug("Extension Configs={}",extensionConfigs);
        List<Extension> extensions = new ArrayList<>();
        for (ExtensionConfig config : extensionConfigs)
        {
            Extension ext = extensionFactory.newInstance(config);
            extensions.add(ext);
            LOG.debug("Adding Extension: {}",ext);
        }

        // Wire up Extensions
        if (extensions.size() > 0)
        {
            Iterator<Extension> extIter;

            // Connect outgoings
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextOutgoingFrames(outgoingHandler);
                outgoingHandler = ext;
            }

            // Connect incomings
            Collections.reverse(extensions);
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextIncomingFrames(incomingHandler);
                incomingHandler = ext;
            }
        }

        addBean(incomingHandler);
        addBean(outgoingHandler);

        // Connect remote
        remote = new WebSocketRemoteEndpoint(connection,outgoingHandler);

        // Activate Session
        this.active = true;

        // Open WebSocket
        websocket.setSession(this);
        websocket.onConnect();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}",dump());
        }
    }

    @Override
    public void ping(ByteBuffer buf) throws IOException
    {
        remote.sendPing(buf);
    }

    @Override
    public void removeMessageHandler(MessageHandler listener)
    {
        messageHandlers.remove(listener);
    }

    public void setActive(boolean active)
    {
        this.active = active;
    }

    @Override
    public void setEncoders(List<Encoder> encoders)
    {
        this.encoders.clear();
        if (encoders != null)
        {
            encoders.addAll(encoders);
        }
    }

    public void setExtensionFactory(ExtensionFactory extensionFactory)
    {
        this.extensionFactory = extensionFactory;
    }

    @Override
    public void setMaximumMessageSize(long length)
    {
        this.maximumMessageSize = length;
    }

    public void setNegotiatedExtensionConfigs(List<ExtensionConfig> extensions)
    {
        this.negotiatedExtensions.clear();
        this.extensionConfigs.clear();

        for (ExtensionConfig config : extensions)
        {
            this.extensionConfigs.add(config);
            this.negotiatedExtensions.add(config.getParameterizedName());
        }
    }

    public void setNegotiatedExtensions(List<String> negotiatedExtensions)
    {
        this.negotiatedExtensions = negotiatedExtensions;

        this.extensionConfigs.clear();
        for (String negotiatedExtension : negotiatedExtensions)
        {
            this.extensionConfigs.add(ExtensionConfig.parse(negotiatedExtension));
        }
    }

    public void setNegotiatedSubprotocol(String negotiatedSubprotocol)
    {
        this.negotiatedSubprotocol = negotiatedSubprotocol;
    }

    public void setOutgoingHandler(OutgoingFrames outgoing)
    {
        this.outgoingHandler = outgoing;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    /**
     * Set the timeout in seconds
     */
    @Override
    public void setTimeout(long seconds)
    {
        this.timeout = seconds;
    }

    @Override
    public SuspendToken suspend()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("WebSocketSession[");
        builder.append("websocket=").append(websocket);
        builder.append(",connection=").append(connection);
        builder.append(",remote=").append(remote);
        builder.append(",incoming=").append(incomingHandler);
        builder.append(",outgoing=").append(outgoingHandler);
        builder.append("]");
        return builder.toString();
    }

    @Override
    public Future<SendResult> write(byte[] buf, int offset, int len) throws IOException
    {
        return remote.sendBytes(ByteBuffer.wrap(buf,offset,len),null);
    }

    @Override
    public Future<SendResult> write(ByteBuffer buffer) throws IOException
    {
        return remote.sendBytes(buffer,null);
    }

    @Override
    public Future<SendResult> write(String message) throws IOException
    {
        return remote.sendString(message,null);
    }
}
