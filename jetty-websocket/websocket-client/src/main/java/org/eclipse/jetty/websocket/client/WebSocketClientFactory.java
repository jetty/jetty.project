package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.EventMethodsCache;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketEventDriver;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.io.WebSocketClientSelectorManager;

public class WebSocketClientFactory extends AggregateLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketClientFactory.class);
    private final Queue<WebSocketConnection> connections = new ConcurrentLinkedQueue<>();
    private final ByteBufferPool bufferPool = new StandardByteBufferPool();
    private final Executor executor;
    private final WebSocketClientSelectorManager selector;
    private final EventMethodsCache methodsCache;
    private final WebSocketPolicy policy;

    public WebSocketClientFactory()
    {
        this(null,null);
    }

    public WebSocketClientFactory(Executor threadPool)
    {
        this(threadPool,null);
    }

    public WebSocketClientFactory(Executor threadPool, SslContextFactory sslContextFactory)
    {
        if (threadPool == null)
        {
            threadPool = new QueuedThreadPool();
        }
        this.executor = threadPool;
        addBean(threadPool);

        if (sslContextFactory != null)
        {
            addBean(sslContextFactory);
        }

        selector = new WebSocketClientSelectorManager(bufferPool,executor);
        selector.setSslContextFactory(sslContextFactory);
        addBean(selector);

        this.methodsCache = new EventMethodsCache();

        this.policy = WebSocketPolicy.newClientPolicy();
    }

    public WebSocketClientFactory(SslContextFactory sslContextFactory)
    {
        this(null,sslContextFactory);
    }

    private void closeConnections()
    {
        for (WebSocketConnection connection : connections)
        {
            try
            {
                connection.close();
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }
        connections.clear();
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
        super.doStop();
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    protected Collection<WebSocketConnection> getConnections()
    {
        return Collections.unmodifiableCollection(connections);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public SelectorManager getSelector()
    {
        return selector;
    }

    public WebSocketClient newSPDYClient()
    {
        return new WebSocketClient(this);
    }

    public WebSocketEventDriver newWebSocketDriver(Object websocketPojo)
    {
        return new WebSocketEventDriver(methodsCache,policy,websocketPojo);
    }
}
