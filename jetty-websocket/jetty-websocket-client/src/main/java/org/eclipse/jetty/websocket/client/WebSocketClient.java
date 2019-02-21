//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.impl.JettyClientUpgradeRequest;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

public class WebSocketClient extends ContainerLifeCycle implements WebSocketPolicy, WebSocketContainer
{
    private static final Logger LOG = Log.getLogger(WebSocketClient.class);
    private final WebSocketCoreClient coreClient;
    private final int id = ThreadLocalRandom.current().nextInt();
    private final JettyWebSocketFrameHandlerFactory frameHandlerFactory;
    private final List<WebSocketSessionListener> sessionListeners = new CopyOnWriteArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();
    private ClassLoader contextClassLoader;
    private DecoratedObjectFactory objectFactory;
    private WebSocketExtensionRegistry extensionRegistry;
    private int inputBufferSize = 4 * 1024;
    private int outputBufferSize = 4 * 1024;
    private long maxBinaryMessageSize = 64 * 1024;
    private long maxTextMessageSize = 64 * 1024;

    /**
     * Instantiate a WebSocketClient with defaults
     */
    public WebSocketClient()
    {
        this(new WebSocketCoreClient());
        this.coreClient.getHttpClient().setName("Jetty-WebSocketClient@" + hashCode());
        // We created WebSocketCoreClient, let lifecycle be managed by us
        addManaged(coreClient);
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient the HttpClient to base internal defaults off of
     */
    public WebSocketClient(HttpClient httpClient)
    {
        this(new WebSocketCoreClient(httpClient));
        // We created WebSocketCoreClient, let lifecycle be managed by us
        addManaged(coreClient);
    }

    private WebSocketClient(WebSocketCoreClient coreClient)
    {
        this.coreClient = coreClient;
        this.contextClassLoader = this.getClass().getClassLoader();
        this.objectFactory = new DecoratedObjectFactory();
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.frameHandlerFactory = new JettyWebSocketFrameHandlerFactory(this);
        this.sessionListeners.add(sessionTracker);
        addBean(sessionTracker);
    }

    public CompletableFuture<Session> connect(Object websocket, URI toUri) throws IOException
    {
        return connect(websocket, toUri, null);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket the websocket object
     * @param toUri     the websocket uri to connect to
     * @param request   the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    public CompletableFuture<Session> connect(Object websocket, URI toUri, UpgradeRequest request) throws IOException
    {
        JettyClientUpgradeRequest upgradeRequest = new JettyClientUpgradeRequest(this, coreClient, request, toUri, websocket);
        coreClient.connect(upgradeRequest);
        return upgradeRequest.getFutureSession();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, getOpenSessions());
    }

    @Override
    public WebSocketBehavior getBehavior()
    {
        return WebSocketBehavior.CLIENT;
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        sessionListeners.add(listener);
    }

    @Override
    public boolean removeSessionListener(WebSocketSessionListener listener)
    {
        return sessionListeners.remove(listener);
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> consumer)
    {
        for (WebSocketSessionListener listener : sessionListeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    @Override
    public Duration getIdleTimeout()
    {
        return Duration.ofMillis(getHttpClient().getIdleTimeout());
    }

    @Override
    public int getInputBufferSize()
    {
        return this.inputBufferSize;
    }

    @Override
    public int getOutputBufferSize()
    {
        return this.outputBufferSize;
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return this.maxBinaryMessageSize;
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return this.maxTextMessageSize;
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        getHttpClient().setIdleTimeout(duration.toMillis());
    }

    @Override
    public void setInputBufferSize(int size)
    {
        this.inputBufferSize = size;
    }

    @Override
    public void setOutputBufferSize(int size)
    {
        this.outputBufferSize = size;
    }

    @Override
    public void setMaxBinaryMessageSize(long size)
    {
        this.maxBinaryMessageSize = size;
    }

    @Override
    public void setMaxTextMessageSize(long size)
    {
        this.maxTextMessageSize = size;
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

    public ByteBufferPool getBufferPool()
    {
        return getHttpClient().getByteBufferPool();
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

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }

    public JettyWebSocketFrameHandler newFrameHandler(Object websocketPojo, UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse,
        CompletableFuture<Session> futureSession)
    {
        return frameHandlerFactory.newJettyFrameHandler(websocketPojo, upgradeRequest, upgradeResponse, futureSession);
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
