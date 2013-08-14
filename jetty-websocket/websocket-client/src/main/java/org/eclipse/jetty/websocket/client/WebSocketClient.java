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

package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.CookieStore;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.io.ConnectPromise;
import org.eclipse.jetty.websocket.client.io.ConnectionManager;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.client.masks.RandomMasker;
import org.eclipse.jetty.websocket.client.masks.ZeroMasker;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSessionFactory;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;

/**
 * WebSocketClient provides a means of establishing connections to remote websocket endpoints.
 */
public class WebSocketClient extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketClient.class);

    private final WebSocketPolicy policy;
    private final SslContextFactory sslContextFactory;
    private final WebSocketExtensionFactory extensionRegistry;
    private EventDriverFactory eventDriverFactory;
    private SessionFactory sessionFactory;
    private ByteBufferPool bufferPool;
    private Executor executor;
    private Scheduler scheduler;
    private CookieStore cookieStore;
    private ConnectionManager connectionManager;
    private Masker masker;
    private SocketAddress bindAddress;
    private long connectTimeout = SelectorManager.DEFAULT_CONNECT_TIMEOUT;

    public WebSocketClient()
    {
        this(null);
    }

    public WebSocketClient(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        this.policy = WebSocketPolicy.newClientPolicy();
        this.bufferPool = new MappedByteBufferPool();
        this.extensionRegistry = new WebSocketExtensionFactory(policy,bufferPool);
        if(LOG.isDebugEnabled()) {
            LOG.debug("Using ZeroMasker (DEBUG)");
            this.masker = new ZeroMasker();
        } else {
            this.masker = new RandomMasker();
        }
        this.eventDriverFactory = new EventDriverFactory(policy);
        this.sessionFactory = new WebSocketSessionFactory();
    }

    public Future<Session> connect(Object websocket, URI toUri) throws IOException
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest(toUri);
        request.setRequestURI(toUri);
        request.setCookiesFrom(this.cookieStore);

        return connect(websocket,toUri,request);
    }

    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request) throws IOException
    {
        return connect(websocket,toUri,request,null);
    }

    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request, UpgradeListener upgradeListener) throws IOException
    {
        if (!isStarted())
        {
            throw new IllegalStateException(WebSocketClient.class.getSimpleName() + "@" + this.hashCode() + " is not started");
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
        request.setCookiesFrom(this.cookieStore);

        // Validate Requested Extensions
        for (ExtensionConfig reqExt : request.getExtensions())
        {
            if (!extensionRegistry.isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        // Validate websocket URI
        LOG.debug("connect websocket {} to {}",websocket,toUri);

        // Grab Connection Manager
        ConnectionManager manager = getConnectionManager();

        // Setup Driver for user provided websocket
        EventDriver driver = null;
        if (websocket instanceof EventDriver)
        {
            // Use the EventDriver as-is
            driver = (EventDriver)websocket;
        }
        else
        {
            // Wrap websocket with appropriate EventDriver
            driver = eventDriverFactory.wrap(websocket);
        }

        if (driver == null)
        {
            throw new IllegalStateException("Unable to identify as websocket object: " + websocket.getClass().getName());
        }

        // Create the appropriate (physical vs virtual) connection task
        ConnectPromise promise = manager.connect(this,driver,request);

        if (upgradeListener != null)
        {
            promise.setUpgradeListener(upgradeListener);
        }

        LOG.debug("Connect Promise: {}",promise);

        // Execute the connection on the executor thread
        executor.execute(promise);

        // Return the future
        return promise;
    }

    @Override
    protected void doStart() throws Exception
    {
        LOG.debug("Starting {}",this);

        if (sslContextFactory != null)
        {
            addBean(sslContextFactory);
        }

        String name = WebSocketClient.class.getSimpleName() + "@" + hashCode();

        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(name);
            executor = threadPool;
        }
        addBean(executor);

        if (bufferPool == null)
        {
            bufferPool = new MappedByteBufferPool();
        }
        addBean(bufferPool);

        if (scheduler == null)
        {
            scheduler = new ScheduledExecutorScheduler(name + "-scheduler",false);
        }
        addBean(scheduler);

        if (cookieStore == null)
        {
            cookieStore = new HttpCookieStore.Empty();
        }

        this.connectionManager = newConnectionManager();
        addBean(this.connectionManager);

        super.doStart();

        LOG.info("Started {}",this);
    }

    @Override
    protected void doStop() throws Exception
    {
        LOG.debug("Stopping {}",this);

        if (cookieStore != null)
        {
            cookieStore.removeAll();
            cookieStore = null;
        }

        super.doStop();
        LOG.info("Stopped {}",this);
    }

    /**
     * Return the number of milliseconds for a timeout of an attempted write operation.
     * 
     * @return number of milliseconds for timeout of an attempted write operation
     */
    public long getAsyncWriteTimeout()
    {
        return this.policy.getAsyncWriteTimeout();
    }

    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public ConnectionManager getConnectionManager()
    {
        return connectionManager;
    }

    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    public CookieStore getCookieStore()
    {
        return cookieStore;
    }

    public EventDriverFactory getEventDriverFactory()
    {
        return eventDriverFactory;
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionRegistry;
    }

    public Masker getMasker()
    {
        return masker;
    }

    /**
     * Get the maximum size for buffering of a binary message.
     * 
     * @return the maximum size of a binary message buffer.
     */
    public int getMaxBinaryMessageBufferSize()
    {
        return this.policy.getMaxBinaryMessageBufferSize();
    }

    /**
     * Get the maximum size for a binary message.
     * 
     * @return the maximum size of a binary message.
     */
    public long getMaxBinaryMessageSize()
    {
        return this.policy.getMaxBinaryMessageSize();
    }

    /**
     * Get the max idle timeout for new connections.
     * 
     * @return the max idle timeout in milliseconds for new connections.
     */
    public long getMaxIdleTimeout()
    {
        return this.policy.getIdleTimeout();
    }

    /**
     * Get the maximum size for buffering of a text message.
     * 
     * @return the maximum size of a text message buffer.
     */
    public int getMaxTextMessageBufferSize()
    {
        return this.policy.getMaxTextMessageBufferSize();
    }

    /**
     * Get the maximum size for a text message.
     * 
     * @return the maximum size of a text message.
     */
    public long getMaxTextMessageSize()
    {
        return this.policy.getMaxTextMessageSize();
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public SessionFactory getSessionFactory()
    {
        return sessionFactory;
    }

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     * @see #WebSocketClient(SslContextFactory)
     */
    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public List<Extension> initExtensions(List<ExtensionConfig> requested)
    {
        List<Extension> extensions = new ArrayList<Extension>();

        for (ExtensionConfig cfg : requested)
        {
            Extension extension = extensionRegistry.newInstance(cfg);

            if (extension == null)
            {
                continue;
            }

            LOG.debug("added {}",extension);
            extensions.add(extension);
        }
        LOG.debug("extensions={}",extensions);
        return extensions;
    }

    /**
     * Factory method for new ConnectionManager (used by other projects like cometd)
     * 
     * @return the ConnectionManager instance to use
     */
    protected ConnectionManager newConnectionManager()
    {
        return new ConnectionManager(this);
    }

    public void setAsyncWriteTimeout(long ms)
    {
        this.policy.setAsyncWriteTimeout(ms);
    }

    public void setBindAdddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public void setBufferPool(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    /**
     * Set the timeout for connecting to the remote server.
     * 
     * @param ms
     *            the timeout in milliseconds
     */
    public void setConnectTimeout(long ms)
    {
        if (ms < 0)
        {
            throw new IllegalStateException("Connect Timeout cannot be negative");
        }
        this.connectTimeout = ms;
    }

    public void setCookieStore(CookieStore cookieStore)
    {
        this.cookieStore = cookieStore;
    }

    public void setEventDriverFactory(EventDriverFactory factory)
    {
        this.eventDriverFactory = factory;
    }

    public void setExecutor(Executor executor)
    {
        this.executor = executor;
    }

    public void setMasker(Masker masker)
    {
        this.masker = masker;
    }

    public void setMaxBinaryMessageBufferSize(int max)
    {
        this.policy.setMaxBinaryMessageBufferSize(max);
    }

    /**
     * Set the max idle timeout for new connections.
     * <p>
     * Existing connections will not have their max idle timeout adjusted.
     * 
     * @param ms
     *            the timeout in milliseconds
     */
    public void setMaxIdleTimeout(long ms)
    {
        this.policy.setIdleTimeout(ms);
    }

    public void setMaxTextMessageBufferSize(int max)
    {
        this.policy.setMaxTextMessageBufferSize(max);
    }

    public void setSessionFactory(SessionFactory sessionFactory)
    {
        this.sessionFactory = sessionFactory;
    }
}
