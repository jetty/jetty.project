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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.internal.ConnectionManager;
import org.eclipse.jetty.websocket.client.internal.DefaultWebSocketClient;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.client.masks.RandomMasker;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;

public class WebSocketClientFactory extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketClientFactory.class);

    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final Executor executor;
    private final Scheduler scheduler;
    private final EventDriverFactory eventDriverFactory;
    private final WebSocketPolicy policy;
    private final WebSocketExtensionFactory extensionRegistry;
    private SocketAddress bindAddress;
    private Masker masker;

    private final Queue<WebSocketSession> sessions = new ConcurrentLinkedQueue<>();
    private ConnectionManager connectionManager;

    public WebSocketClientFactory()
    {
        this(new QueuedThreadPool());
    }

    public WebSocketClientFactory(Executor threadPool)
    {
        this(threadPool,new TimerScheduler());
    }

    public WebSocketClientFactory(Executor threadPool, Scheduler scheduler)
    {
        this(threadPool,scheduler,null);
    }

    public WebSocketClientFactory(Executor executor, Scheduler scheduler, SslContextFactory sslContextFactory)
    {
        LOG.debug("new WebSocketClientFactory()");
        if (executor == null)
        {
            throw new IllegalArgumentException("Executor is required");
        }
        this.executor = executor;
        addBean(executor);

        if (scheduler == null)
        {
            throw new IllegalArgumentException("Scheduler is required");
        }
        this.scheduler = scheduler;
        addBean(scheduler);

        if (sslContextFactory != null)
        {
            addBean(sslContextFactory);
        }

        this.policy = WebSocketPolicy.newClientPolicy();
        this.extensionRegistry = new WebSocketExtensionFactory(policy,bufferPool);

        this.connectionManager = new ConnectionManager(bufferPool,executor,scheduler,sslContextFactory,policy);
        addBean(this.connectionManager);

        this.eventDriverFactory = new EventDriverFactory(policy);
        this.masker = new RandomMasker();
    }

    public WebSocketClientFactory(SslContextFactory sslContextFactory)
    {
        this(new QueuedThreadPool(),new TimerScheduler(),sslContextFactory);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        LOG.debug("doStart()");
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        LOG.debug("doStop()");
    }

    /**
     * The address to bind local physical (outgoing) TCP Sockets to.
     * 
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
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

    public Executor getExecutor()
    {
        return executor;
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionRegistry;
    }

    /**
     * 
     * @return the masker or null if none is set
     */
    public Masker getMasker()
    {
        return masker;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
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

    public WebSocketClient newWebSocketClient(Object websocketPojo)
    {
        LOG.debug("Creating new WebSocket for {}",websocketPojo);
        EventDriver websocket = eventDriverFactory.wrap(websocketPojo);
        DefaultWebSocketClient client = new DefaultWebSocketClient(this,websocket);
        client.setMasker(masker);
        return client;
    }

    public boolean sessionClosed(WebSocketSession session)
    {
        return isRunning() && sessions.remove(session);
    }

    public boolean sessionOpened(WebSocketSession session)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Session Opened: {}",session);
        }
        boolean ret = sessions.offer(session);
        session.open();
        return ret;
    }

    /**
     * @param bindAddress
     *            the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public void setMasker(Masker masker)
    {
        this.masker = masker;
    }
}
