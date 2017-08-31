//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.impl;

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
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.UpgradeListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.SessionListener;
import org.eclipse.jetty.websocket.common.WSSession;
import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.WSRemoteEndpoint;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.WSExtensionRegistry;

/**
 * WebSocketClient provides a means of establishing connections to remote websocket endpoints.
 */
public class WebSocketClientImpl extends ContainerLifeCycle implements WebSocketClient, SessionListener
{
    private static final Logger LOG = Log.getLogger(WebSocketClientImpl.class);
    
    // From HttpClient
    private final HttpClient httpClient;
    
    // The container
    private final WSPolicy clientPolicy;
    private final WSExtensionRegistry extensionRegistry;
    private final DecoratedObjectFactory objectFactory;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    
    private final int id = ThreadLocalRandom.current().nextInt();

    /**
     * Instantiate a WebSocketClient with defaults
     */
    public WebSocketClientImpl()
    {
        // Create synthetic HttpClient
        this(HttpClientProvider.get(null));
        addBean(this.httpClient);
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient
     *            the HttpClient to base internal defaults off of
     */
    public WebSocketClientImpl(HttpClient httpClient)
    {
        this(httpClient,new DecoratedObjectFactory());
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient
     *            the HttpClient to base internal defaults off of
     * @param objectFactory
     *            the DecoratedObjectFactory for all client instantiated classes
     */
    public WebSocketClientImpl(HttpClient httpClient, DecoratedObjectFactory objectFactory)
    {
        this.clientPolicy = WSPolicy.newClientPolicy();
        this.httpClient = httpClient;
        this.extensionRegistry = new WSExtensionRegistry();
        if (objectFactory == null)
        {
            this.objectFactory = new DecoratedObjectFactory();
        }
        else
        {
            this.objectFactory = objectFactory;
        }
    }

    @Override
    public Future<Session> connect(Object websocket, URI toUri) throws IOException
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setRequestURI(toUri);

        return connect(websocket,toUri,request);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket
     *            the websocket object
     * @param toUri
     *            the websocket uri to connect to
     * @param request
     *            the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException
     *             if unable to connect
     */
    @Override
    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request) throws IOException
    {
        return connect(websocket,toUri,request,(UpgradeListener)null);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket
     *            the websocket object
     * @param toUri
     *            the websocket uri to connect to
     * @param request
     *            the upgrade request information
     * @param upgradeListener
     *            the upgrade listener
     * @return the future for the session, available on success of connect
     * @throws IOException
     *             if unable to connect
     */
    @Override
    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request, UpgradeListener upgradeListener) throws IOException
    {
        /* Note: UpgradeListener is used by javax.websocket.ClientEndpointConfig.Configurator
         * See: org.eclipse.jetty.websocket.jsr356.JsrUpgradeListener
         */
        if (!isStarted())
        {
            throw new IllegalStateException(WebSocketClientImpl.class.getSimpleName() + "@" + this.hashCode() + " is not started");
        }

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

        request.setRequestURI(toUri);

        // Validate Requested Extensions
        for (ExtensionConfig reqExt : request.getExtensions())
        {
            if (!extensionRegistry.isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("connect websocket {} to {}",websocket,toUri);

        init();

        WebSocketUpgradeRequest wsReq = new WebSocketUpgradeRequest(this,request,websocket);
        wsReq.setUpgradeListener(upgradeListener);
        return wsReq.sendAsync();
    }

    public WSSession createSession(URI requestURI, WebSocketClientConnection connection)
    {
        WSSession session = new WSSession(connection);
        WSPolicy policy;
        WSLocalEndpoint localEndpoint;
        WSRemoteEndpoint remoteEndpoint;

        session.setWebSocketEndpoint(endpointObj, policy, localEndpoint, remoteEndpoint);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}",this);

        if (ShutdownThread.isRegistered(this))
        {
            ShutdownThread.deregister(this);
        }

        super.doStop();

        if (LOG.isDebugEnabled())
            LOG.debug("Stopped {}",this);
    }

    /**
     * Return the number of milliseconds for a timeout of an attempted write operation.
     *
     * @return number of milliseconds for timeout of an attempted write operation
     */
    @Override
    public long getAsyncWriteTimeout()
    {
        return getPolicy().getAsyncWriteTimeout();
    }

    @Override
    public SocketAddress getBindAddress()
    {
        return httpClient.getBindAddress();
    }

    public ByteBufferPool getBufferPool()
    {
        return httpClient.getByteBufferPool();
    }

    @Override
    public long getConnectTimeout()
    {
        return httpClient.getConnectTimeout();
    }

    @Override
    public CookieStore getCookieStore()
    {
        return httpClient.getCookieStore();
    }
    
    public Executor getExecutor()
    {
        return httpClient.getExecutor();
    }

    // Internal getExecutor for defaulting to internal executor if not provided
    private Executor getExecutor(final Executor executor)
    {
        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            String name = "WebSocketClient@" + hashCode();
            threadPool.setName(name);
            threadPool.setDaemon(true);
            return threadPool;
        }
        
        return executor;
    }
    
    public WSExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }
    
    /**
     * Get the maximum size for a binary message.
     *
     * @return the maximum size of a binary message.
     */
    @Override
    public long getMaxBinaryMessageSize()
    {
        return getPolicy().getMaxBinaryMessageSize();
    }

    /**
     * Set the maximum size for a binary message.
     */
    @Override
    public void setMaxBinaryMessageSize(long size)
    {
        getPolicy().setMaxBinaryMessageSize(size);
    }

    /**
     * Get the max idle timeout for new connections.
     *
     * @return the max idle timeout in milliseconds for new connections.
     */
    @Override
    public long getMaxIdleTimeout()
    {
        return getPolicy().getIdleTimeout();
    }

    /**
     * Get the maximum size for a text message.
     *
     * @return the maximum size of a text message.
     */
    @Override
    public long getMaxTextMessageSize()
    {
        return getPolicy().getMaxTextMessageSize();
    }

    /**
     * Set the maximum size for a text message.
     */
    @Override
    public void setMaxTextMessageSize(long size)
    {
        getPolicy().setMaxTextMessageSize(size);
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    @Override
    public Set<Session> getOpenSessions()
    {
        return Collections.unmodifiableSet(new HashSet<>(getBeans(Session.class)));
    }

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     * @see #WebSocketClientImpl(SslContextFactory)
     */
    @Override
    public SslContextFactory getSslContextFactory()
    {
        return httpClient.getSslContextFactory();
    }

    private synchronized void init() throws IOException
    {
        if (!ShutdownThread.isRegistered(this))
        {
            ShutdownThread.register(this);
        }
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

    public void addSessionListener(SessionListener listener)
    {
        this.listeners.add(listener);
    }

    public boolean removeSessionListener(SessionListener listener)
    {
        return this.listeners.remove(listener);
    }

    @Override
    public void onCreated(WSSession session)
    {
        // TODO: implement?
    }

    @Override
    public void onClosed(WSSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session Closed: {}",session);
        removeBean(session);
    }

    @Override
    public void onOpened(WSSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session Opened: {}",session);
        addManaged(session);
        LOG.debug("post-onSessionOpened() - {}", this);
    }

    public void setAsyncWriteTimeout(long ms)
    {
        getPolicy().setAsyncWriteTimeout(ms);
    }

    @Override
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.httpClient.setBindAddress(bindAddress);
    }

    public void setBufferPool(ByteBufferPool bufferPool)
    {
        this.httpClient.setByteBufferPool(bufferPool);
    }

    /**
     * Set the timeout for connecting to the remote server.
     *
     * @param ms
     *            the timeout in milliseconds
     */
    @Override
    public void setConnectTimeout(long ms)
    {
        this.httpClient.setConnectTimeout(ms);
    }

    @Override
    public void setCookieStore(CookieStore cookieStore)
    {
        this.httpClient.setCookieStore(cookieStore);
    }
    
    public void setExecutor(Executor executor)
    {
        this.httpClient.setExecutor(executor);
    }

    /**
     * Set the max idle timeout for new connections.
     * <p>
     * Existing connections will not have their max idle timeout adjusted.
     *
     * @param ms
     *            the timeout in milliseconds
     */
    @Override
    public void setMaxIdleTimeout(long ms)
    {
        getPolicy().setIdleTimeout(ms);
        httpClient.setIdleTimeout(ms);
    }

    protected WSPolicy getPolicy()
    {
        return this.clientPolicy;
    }
    
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,getOpenSessions());
    }

    @Override
    public HttpClient getHttpClient()
    {
        return this.httpClient;
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
}
