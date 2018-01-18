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

package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.CookieStore;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.client.impl.ClientUpgradeRequestImpl;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.WebSocketContainerContext;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

public class WebSocketClient extends ContainerLifeCycle implements WebSocketContainerContext
{
    private final WebSocketCoreClient coreClient;
    private final int id = ThreadLocalRandom.current().nextInt();
    private final JettyWebSocketFrameHandlerFactory frameHandlerFactory;
    private ClassLoader contextClassLoader;
    private DecoratedObjectFactory objectFactory;
    private WebSocketExtensionRegistry extensionRegistry;

    /**
     * Instantiate a WebSocketClient with defaults
     */
    public WebSocketClient()
    {
        this(new WebSocketCoreClient());
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient the HttpClient to base internal defaults off of
     */
    public WebSocketClient(HttpClient httpClient)
    {
        this(new WebSocketCoreClient(httpClient));
    }

    private WebSocketClient(WebSocketCoreClient coreClient)
    {
        this.coreClient = coreClient;
        this.contextClassLoader = this.getClass().getClassLoader();
        this.objectFactory = new DecoratedObjectFactory();
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.frameHandlerFactory = new JettyWebSocketFrameHandlerFactory(getExecutor());
    }

    public CompletableFuture<Session> connect(Object websocket, URI toUri) throws IOException
    {
        ClientUpgradeRequestImpl upgradeRequest = new ClientUpgradeRequestImpl(this, coreClient, null, toUri, websocket);
        coreClient.connect(upgradeRequest);
        return upgradeRequest.getFutureSession();
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket the websocket object
     * @param toUri the websocket uri to connect to
     * @param request the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    public CompletableFuture<Session> connect(Object websocket, URI toUri, UpgradeRequest request) throws IOException
    {
        ClientUpgradeRequestImpl upgradeRequest = new ClientUpgradeRequestImpl(this, coreClient, request, toUri, websocket);
        coreClient.connect(upgradeRequest);
        return upgradeRequest.getFutureSession();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        dump(out, indent, getOpenSessions());
    }

    /**
     * Return the number of milliseconds for a timeout of an attempted write operation.
     *
     * @return number of milliseconds for timeout of an attempted write operation
     */
    public long getAsyncWriteTimeout()
    {
        return getPolicy().getAsyncWriteTimeout();
    }

    public void setAsyncWriteTimeout(long ms)
    {
        getPolicy().setAsyncWriteTimeout(ms);
    }

    public SocketAddress getBindAddress()
    {
        return getHttpClient().getBindAddress();
    }

    public void setBindAddress(SocketAddress bindAddress)
    {
        getHttpClient().setBindAddress(bindAddress);
    }

    public long getConnectTimeout()
    {
        return getHttpClient().getConnectTimeout();
    }

    /**
     * Set the timeout for connecting to the remote server.
     *
     * @param ms the timeout in milliseconds
     */
    public void setConnectTimeout(long ms)
    {
        getHttpClient().setConnectTimeout(ms);
    }

    public CookieStore getCookieStore()
    {
        return getHttpClient().getCookieStore();
    }

    public void setCookieStore(CookieStore cookieStore)
    {
        getHttpClient().setCookieStore(cookieStore);
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return getHttpClient().getByteBufferPool();
    }

    @Override
    public ClassLoader getContextClassloader()
    {
        return this.contextClassLoader;
    }

    @Override
    public Executor getExecutor()
    {
        return getHttpClient().getExecutor();
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    public HttpClient getHttpClient()
    {
        return coreClient.getHttpClient();
    }

    /**
     * Get the maximum size for a binary message.
     *
     * @return the maximum size of a binary message.
     */
    public long getMaxBinaryMessageSize()
    {
        return getPolicy().getMaxBinaryMessageSize();
    }

    /**
     * Set the maximum size for a binary message.
     */
    public void setMaxBinaryMessageSize(long size)
    {
        getPolicy().setMaxBinaryMessageSize(size);
    }

    /**
     * Get the max idle timeout for new connections.
     *
     * @return the max idle timeout in milliseconds for new connections.
     */
    public long getMaxIdleTimeout()
    {
        return getPolicy().getIdleTimeout();
    }

    /**
     * Set the max idle timeout for new connections.
     * <p>
     * Existing connections will not have their max idle timeout adjusted.
     *
     * @param ms the timeout in milliseconds
     */
    public void setMaxIdleTimeout(long ms)
    {
        getPolicy().setIdleTimeout(ms);
        getHttpClient().setIdleTimeout(ms);
    }

    /**
     * Get the maximum size for a text message.
     *
     * @return the maximum size of a text message.
     */
    public long getMaxTextMessageSize()
    {
        return getPolicy().getMaxTextMessageSize();
    }

    /**
     * Set the maximum size for a text message.
     */
    public void setMaxTextMessageSize(long size)
    {
        getPolicy().setMaxTextMessageSize(size);
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public Set<Session> getOpenSessions()
    {
        return Collections.unmodifiableSet(new HashSet<>(getBeans(Session.class)));
    }

    public WebSocketPolicy getPolicy()
    {
        return coreClient.getPolicy();
    }

    public JettyWebSocketFrameHandler newFrameHandler(Object websocketPojo, WebSocketPolicy policy, HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse, CompletableFuture<Session> futureSession)
    {
        return frameHandlerFactory.newJettyFrameHandler(websocketPojo, policy, handshakeRequest, handshakeResponse, futureSession);
    }

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     */
    public SslContextFactory getSslContextFactory()
    {
        return getHttpClient().getSslContextFactory();
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder("WebSocketClient@");
        sb.append(Integer.toHexString(id));
        sb.append("[coreClient=").append(coreClient);
        sb.append(",openSessions.size=");
        sb.append(getOpenSessions().size());
        sb.append(']');
        return sb.toString();
    }
}
