//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.client.impl.JettyClientUpgradeRequest;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketClient extends ContainerLifeCycle implements WebSocketPolicy, WebSocketContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClient.class);
    private final WebSocketCoreClient coreClient;
    private final int id = ThreadLocalRandom.current().nextInt();
    private final JettyWebSocketFrameHandlerFactory frameHandlerFactory;
    private final List<WebSocketSessionListener> sessionListeners = new CopyOnWriteArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();
    private final Configuration.ConfigurationCustomizer configurationCustomizer = new Configuration.ConfigurationCustomizer();
    private final WebSocketComponents components = new WebSocketComponents();
    private boolean stopAtShutdown = false;
    private long _stopTimeout = Long.MAX_VALUE;

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
        frameHandlerFactory = new JettyWebSocketFrameHandlerFactory(this, components);
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
    public CompletableFuture<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request) throws IOException
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
    public CompletableFuture<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request, JettyUpgradeListener upgradeListener) throws IOException
    {
        for (Connection.Listener listener : getBeans(Connection.Listener.class))
        {
            coreClient.addBean(listener);
        }

        JettyClientUpgradeRequest upgradeRequest = new JettyClientUpgradeRequest(coreClient, request, toUri, frameHandlerFactory, websocket);
        upgradeRequest.setConfiguration(configurationCustomizer);
        for (Request.Listener l : getBeans(Request.Listener.class))
        {
            upgradeRequest.listener(l);
        }

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

        CompletableFuture<Session> futureSession = new CompletableFuture<>();
        CompletableFuture<CoreSession> coreConnect = coreClient.connect(upgradeRequest);
        coreConnect.whenComplete((coreSession, error) ->
        {
            if (error != null)
            {
                futureSession.completeExceptionally(JettyWebSocketFrameHandler.convertCause(error));
                return;
            }

            JettyWebSocketFrameHandler frameHandler = (JettyWebSocketFrameHandler)upgradeRequest.getFrameHandler();
            futureSession.complete(frameHandler.getSession());
        });

        // If the returned future is cancelled we want to try to cancel the core future if possible.
        futureSession.whenComplete((session, throwable) ->
        {
            if (throwable != null)
                coreConnect.completeExceptionally(throwable);
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
                LOG.info("Exception while invoking listener {}", listener, x);
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
    public long getMaxFrameSize()
    {
        return configurationCustomizer.getMaxFrameSize();
    }

    @Override
    public boolean isAutoFragment()
    {
        return configurationCustomizer.isAutoFragment();
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        configurationCustomizer.setIdleTimeout(duration);
        getHttpClient().setIdleTimeout(duration.toMillis());
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

    @Override
    public void setMaxFrameSize(long maxFrameSize)
    {
        configurationCustomizer.setMaxFrameSize(maxFrameSize);
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {
        configurationCustomizer.setAutoFragment(autoFragment);
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

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     */
    public SslContextFactory getSslContextFactory()
    {
        return getHttpClient().getSslContextFactory();
    }

    /**
     * Set JVM shutdown behavior.
     * @param stop If true, this client instance will be explicitly stopped when the
     * JVM is shutdown. Otherwise the application is responsible for maintaining the WebSocketClient lifecycle.
     * @see Runtime#addShutdownHook(Thread)
     * @see ShutdownThread
     */
    public void setStopAtShutdown(boolean stop)
    {
        if (stop)
        {
            if (!stopAtShutdown && !ShutdownThread.isRegistered(this))
                ShutdownThread.register(this);
        }
        else
            ShutdownThread.deregister(this);

        stopAtShutdown = stop;
    }

    /**
     * The timeout to allow all remaining open Sessions to be closed gracefully using  the close code {@link org.eclipse.jetty.websocket.api.StatusCode#SHUTDOWN}.
     * @param stopTimeout the time in ms to wait for the graceful close, use a value less than or equal to 0 to not gracefully close.
     */
    public void setStopTimeout(long stopTimeout)
    {
        _stopTimeout = stopTimeout;
    }

    public long getStopTimeout()
    {
        return _stopTimeout;
    }

    public boolean isStopAtShutdown()
    {
        return stopAtShutdown;
    }

    @Override
    protected void doStop() throws Exception
    {
        if (getStopTimeout() > 0)
            Graceful.shutdown(this).get(getStopTimeout(), TimeUnit.MILLISECONDS);
        super.doStop();
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
