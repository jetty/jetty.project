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
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.client.impl.JettyClientUpgradeRequest;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

public class WebSocketClient extends ContainerLifeCycle implements WebSocketPolicy, WebSocketContainer
{
    private static final Logger LOG = Log.getLogger(WebSocketClient.class);
    private final WebSocketCoreClient coreClient;
    private final int id = ThreadLocalRandom.current().nextInt();
    private final JettyWebSocketFrameHandlerFactory frameHandlerFactory;
    private final List<WebSocketSessionListener> sessionListeners = new CopyOnWriteArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();
    private final FrameHandler.ConfigurationCustomizer configurationCustomizer = new FrameHandler.ConfigurationCustomizer();
    private WebSocketComponents components = new WebSocketComponents();

    /**
     * Instantiate a WebSocketClient with defaults
     */
    public WebSocketClient()
    {
        this(null);
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient the HttpClient to base internal defaults off of
     */
    public WebSocketClient(HttpClient httpClient)
    {
        coreClient = new WebSocketCoreClient(httpClient, components);
        addManaged(coreClient);

        if (httpClient == null)
            coreClient.getHttpClient().setName("Jetty-WebSocketClient@" + hashCode());

        frameHandlerFactory = new JettyWebSocketFrameHandlerFactory(this);
        sessionListeners.add(sessionTracker);
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
     * @param toUri the websocket uri to connect to
     * @param request the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    public CompletableFuture<Session> connect(Object websocket, URI toUri, UpgradeRequest request) throws IOException
    {
        return connect(websocket, toUri, request, null);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket the websocket object
     * @param toUri the websocket uri to connect to
     * @param request the upgrade request information
     * @param upgradeListener the upgrade listener
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    public CompletableFuture<Session> connect(Object websocket, URI toUri, UpgradeRequest request, JettyUpgradeListener upgradeListener) throws IOException
    {
        for (Connection.Listener listener : getBeans(Connection.Listener.class))
        {
            coreClient.addBean(listener);
        }

        JettyClientUpgradeRequest upgradeRequest = new JettyClientUpgradeRequest(this, coreClient, request, toUri, websocket);
        if (upgradeListener != null)
        {
            upgradeRequest.addListener(new UpgradeListener()
            {
                @Override
                public void onHandshakeRequest(HttpRequest request)
                {
                    upgradeListener.onHandshakeRequest(request);
                }

                @Override
                public void onHandshakeResponse(HttpRequest request, HttpResponse response)
                {
                    upgradeListener.onHandshakeResponse(request, response);
                }
            });
        }
        upgradeRequest.setConfiguration(configurationCustomizer);
        CompletableFuture<Session> futureSession = new CompletableFuture<>();

        coreClient.connect(upgradeRequest).whenComplete((coreSession, error) ->
        {
            if (error != null)
            {
                futureSession.completeExceptionally(JettyWebSocketFrameHandler.convertCause(error));
                return;
            }

            JettyWebSocketFrameHandler frameHandler = (JettyWebSocketFrameHandler)upgradeRequest.getFrameHandler();
            futureSession.complete(frameHandler.getSession());
        });

        return futureSession;
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
        return configurationCustomizer.getIdleTimeout();
    }

    @Override
    public int getInputBufferSize()
    {
        return configurationCustomizer.getInputBufferSize();
    }

    @Override
    public int getOutputBufferSize()
    {
        return configurationCustomizer.getOutputBufferSize();
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return configurationCustomizer.getMaxBinaryMessageSize();
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return configurationCustomizer.getMaxTextMessageSize();
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        configurationCustomizer.setIdleTimeout(duration);
    }

    @Override
    public void setInputBufferSize(int size)
    {
        configurationCustomizer.setInputBufferSize(size);
    }

    @Override
    public void setOutputBufferSize(int size)
    {
        configurationCustomizer.setOutputBufferSize(size);
    }

    @Override
    public void setMaxBinaryMessageSize(long size)
    {
        configurationCustomizer.setMaxBinaryMessageSize(size);
    }

    @Override
    public void setMaxTextMessageSize(long size)
    {
        configurationCustomizer.setMaxTextMessageSize(size);
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

    public HttpClient getHttpClient()
    {
        return coreClient.getHttpClient();
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return components.getObjectFactory();
    }

    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }

    public JettyWebSocketFrameHandler newFrameHandler(Object websocketPojo)
    {
        return frameHandlerFactory.newJettyFrameHandler(websocketPojo);
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
