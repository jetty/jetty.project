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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.impl.HttpClientProvider;
import org.eclipse.jetty.websocket.client.impl.WebSocketClientConnection;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerImpl;
import org.eclipse.jetty.websocket.common.JettyWebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.common.SessionListener;
import org.eclipse.jetty.websocket.common.WebSocketSessionImpl;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

public class WebSocketClient extends ContainerLifeCycle implements SessionListener
{
    private static final Logger LOG = Log.getLogger(WebSocketClient.class);
    // From HttpClient
    private final HttpClient httpClient;
    // The container
    private final WebSocketPolicy clientPolicy;
    private final WebSocketExtensionRegistry extensionRegistry;
    private final DecoratedObjectFactory objectFactory;
    private final JettyWebSocketFrameHandlerFactory localEndpointFactory;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final int id = ThreadLocalRandom.current().nextInt();
    protected Function<WebSocketClientConnection, WebSocketSessionImpl<
            WebSocketClient, WebSocketClientConnection,
            JettyWebSocketFrameHandlerImpl, JettyWebSocketRemoteEndpoint>> newSessionFunction =
            (connection) -> new WebSocketSessionImpl(WebSocketClient.this, connection);

    /**
     * Instantiate a WebSocketClient with defaults
     */
    public WebSocketClient()
    {
        // Create synthetic HttpClient
        this(HttpClientProvider.get(null));
        addBean(this.httpClient);
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient the HttpClient to base internal defaults off of
     */
    public WebSocketClient(HttpClient httpClient)
    {
        this(httpClient, new DecoratedObjectFactory());
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient the HttpClient to base internal defaults off of
     * @param objectFactory the DecoratedObjectFactory for all client instantiated classes
     */
    public WebSocketClient(HttpClient httpClient, DecoratedObjectFactory objectFactory)
    {
        this.clientPolicy = WebSocketPolicy.newClientPolicy();
        this.httpClient = httpClient;
        this.extensionRegistry = new WebSocketExtensionRegistry();
        if (objectFactory == null)
        {
            this.objectFactory = new DecoratedObjectFactory();
        }
        else
        {
            this.objectFactory = objectFactory;
        }
        this.localEndpointFactory = new JettyWebSocketFrameHandlerFactory();
    }

    public void addSessionListener(SessionListener listener)
    {
        this.listeners.add(listener);
    }

    public Future<Session> connect(Object websocket, URI toUri) throws IOException
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setRequestURI(toUri);

        return connect(websocket, toUri, request);
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
    public Future<Session> connect(Object websocket, URI toUri, UpgradeRequest request) throws IOException
    {
        return connect(websocket, toUri, request, (UpgradeListener) null);
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
    public Future<Session> connect(Object websocket, URI toUri, UpgradeRequest request, UpgradeListener upgradeListener) throws IOException
    {
        if (request instanceof WebSocketUpgradeRequest)
        {
            return connect(websocket, (WebSocketUpgradeRequest) request, upgradeListener);
        }
        else
        {
            request.setRequestURI(toUri);
            return connect(websocket, new WebSocketUpgradeRequest(this, request), upgradeListener);
        }
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket the websocket object
     * @param request the upgrade request information
     * @param upgradeListener the upgrade listener
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    public Future<Session> connect(Object websocket, WebSocketUpgradeRequest request, UpgradeListener upgradeListener) throws IOException
    {
        /* Note: UpgradeListener is used by javax.websocket.ClientEndpointConfig.Configurator
         * See: org.eclipse.jetty.websocket.jsr356.JsrUpgradeListener
         */
        if (!isStarted())
        {
            throw new IllegalStateException(WebSocketClient.class.getSimpleName() + "@" + this.hashCode() + " is not started");
        }

        URI toUri = request.getURI();

        // Validate websocket URI
        if (!toUri.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be absolute");
        }

        if (StringUtil.isBlank(toUri.getScheme()))
        {
            throw new IllegalArgumentException("WebSocket URI must include a scheme");
        }

        String scheme = toUri.getScheme().toLowerCase(Locale.ENGLISH);
        if (("ws".equals(scheme) == false) && ("wss".equals(scheme) == false))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }

        // Validate Requested Extensions
        for (ExtensionConfig reqExt : request.getExtensions())
        {
            if (!extensionRegistry.isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("connect websocket {} to {}", websocket, toUri);

        init();

        request.setWebSocket(websocket);
        request.setUpgradeListener(upgradeListener);
        return request.sendAsync();
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
        return httpClient.getBindAddress();
    }

    public void setBindAddress(SocketAddress bindAddress)
    {
        this.httpClient.setBindAddress(bindAddress);
    }

    public long getConnectTimeout()
    {
        return httpClient.getConnectTimeout();
    }

    /**
     * Set the timeout for connecting to the remote server.
     *
     * @param ms the timeout in milliseconds
     */
    public void setConnectTimeout(long ms)
    {
        this.httpClient.setConnectTimeout(ms);
    }

    public CookieStore getCookieStore()
    {
        return httpClient.getCookieStore();
    }

    public void setCookieStore(CookieStore cookieStore)
    {
        this.httpClient.setCookieStore(cookieStore);
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    public HttpClient getHttpClient()
    {
        return this.httpClient;
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
        httpClient.setIdleTimeout(ms);
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
        return this.clientPolicy;
    }

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     */
    public SslContextFactory getSslContextFactory()
    {
        return httpClient.getSslContextFactory();
    }

    @Override
    public void onClosed(WebSocketSessionImpl session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session Closed: {}", session);
        removeBean(session);
    }

    @Override
    public void onCreated(WebSocketSessionImpl session)
    {
        // TODO: implement?
    }

    @Override
    public void onOpened(WebSocketSessionImpl session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session Opened: {}", session);
        addManaged(session);
        LOG.debug("post-onSessionOpened() - {}", this);
    }

    public boolean removeSessionListener(SessionListener listener)
    {
        return this.listeners.remove(listener);
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder("WebSocketClient@");
        sb.append(Integer.toHexString(id));
        sb.append("[httpClient=").append(httpClient);
        sb.append(",openSessions.size=");
        sb.append(getOpenSessions().size());
        sb.append(']');
        return sb.toString();
    }

    protected WebSocketSessionImpl createSession(WebSocketClientConnection connection, Object endpointInstance)
    {
        WebSocketSessionImpl session = newSessionFunction.apply(connection);
        JettyWebSocketFrameHandlerImpl localEndpoint = localEndpointFactory.createLocalEndpoint(endpointInstance, session, getPolicy(), httpClient.getExecutor());
        JettyWebSocketRemoteEndpoint remoteEndpoint = new JettyWebSocketRemoteEndpoint(connection, connection.getRemoteAddress());

        session.setWebSocketEndpoint(endpointInstance, localEndpoint.getPolicy(), localEndpoint, remoteEndpoint);
        return session;
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}", this);

        if (ShutdownThread.isRegistered(this))
        {
            ShutdownThread.deregister(this);
        }

        super.doStop();

        if (LOG.isDebugEnabled())
            LOG.debug("Stopped {}", this);
    }

    protected void notifySessionListeners(Consumer<SessionListener> consumer)
    {
        for (SessionListener listener : listeners)
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

    private synchronized void init() throws IOException
    {
        if (!ShutdownThread.isRegistered(this))
        {
            ShutdownThread.register(this);
        }
    }
}
