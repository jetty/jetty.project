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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.CookieStore;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;

/**
 * <p>{@link HttpClient} provides an efficient, asynchronous, non-blocking implementation
 * to perform HTTP requests to a server through a simple API that offers also blocking semantic.</p>
 * <p>{@link HttpClient} provides easy-to-use methods such as {@link #GET(String)} that allow to perform HTTP
 * requests in a one-liner, but also gives the ability to fine tune the configuration of requests via
 * {@link HttpClient#newRequest(URI)}.</p>
 * <p>{@link HttpClient} acts as a central configuration point for network parameters (such as idle timeouts)
 * and HTTP parameters (such as whether to follow redirects).</p>
 * <p>{@link HttpClient} transparently pools connections to servers, but allows direct control of connections
 * for cases where this is needed.</p>
 * <p>{@link HttpClient} also acts as a central configuration point for cookies, via {@link #getCookieStore()}.</p>
 * <p>Typical usage:</p>
 * <pre>
 * // One liner:
 * new HttpClient().GET("http://localhost:8080/").get().status();
 *
 * // Building a request with a timeout
 * HttpClient client = new HttpClient();
 * Response response = client.newRequest("http://localhost:8080").send().get(5, TimeUnit.SECONDS);
 * int status = response.status();
 *
 * // Asynchronously
 * HttpClient client = new HttpClient();
 * client.newRequest("http://localhost:8080").send(new Response.Listener.Empty()
 * {
 *     &#64;Override
 *     public void onSuccess(Response response)
 *     {
 *         ...
 *     }
 * });
 * </pre>
 */
public class HttpClient extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(HttpClient.class);

    private final ConcurrentMap<String, HttpDestination> destinations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, HttpConversation> conversations = new ConcurrentHashMap<>();
    private final List<ProtocolHandler> handlers = new CopyOnWriteArrayList<>();
    private final List<Request.Listener> requestListeners = new CopyOnWriteArrayList<>();
    private final CookieStore cookieStore = new HttpCookieStore();
    private final AuthenticationStore authenticationStore = new HttpAuthenticationStore();
    private final Set<ContentDecoder.Factory> decoderFactories = Collections.newSetFromMap(new ConcurrentHashMap<ContentDecoder.Factory, Boolean>());
    private final SslContextFactory sslContextFactory;
    private volatile Executor executor;
    private volatile ByteBufferPool byteBufferPool;
    private volatile Scheduler scheduler;
    private volatile SelectorManager selectorManager;
    private volatile String agent = "Jetty/" + Jetty.VERSION;
    private volatile boolean followRedirects = true;
    private volatile int maxConnectionsPerAddress = 8;
    private volatile int maxQueueSizePerAddress = 1024;
    private volatile int requestBufferSize = 4096;
    private volatile int responseBufferSize = 4096;
    private volatile int maxRedirects = 8;
    private volatile SocketAddress bindAddress;
    private volatile long connectTimeout = 15000;
    private volatile long idleTimeout;
    private volatile boolean tcpNoDelay = true;
    private volatile boolean dispatchIO = true;

    public HttpClient()
    {
        this(null);
    }

    public HttpClient(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (sslContextFactory != null)
        {
            addBean(sslContextFactory);
            // Avoid to double dispatch when using SSL
            setDispatchIO(false);
        }

        String name = HttpClient.class.getSimpleName() + "@" + hashCode();

        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(name);
            executor = threadPool;
        }
        addBean(executor);

        if (byteBufferPool == null)
            byteBufferPool = new MappedByteBufferPool();
        addBean(byteBufferPool);

        if (scheduler == null)
            scheduler = new TimerScheduler(name + "-scheduler");
        addBean(scheduler);

        selectorManager = newSelectorManager();
        selectorManager.setConnectTimeout(getConnectTimeout());
        addBean(selectorManager);

        handlers.add(new ContinueProtocolHandler(this));
        handlers.add(new RedirectProtocolHandler(this));
        handlers.add(new AuthenticationProtocolHandler(this));

        decoderFactories.add(new GZIPContentDecoder.Factory());

        super.doStart();

        LOG.info("Started {}", this);
    }

    protected SelectorManager newSelectorManager()
    {
        return new ClientSelectorManager(getExecutor(), getScheduler());
    }

    @Override
    protected void doStop() throws Exception
    {
        LOG.debug("Stopping {}", this);

        for (HttpDestination destination : destinations.values())
            destination.close();

        destinations.clear();
        conversations.clear();
        handlers.clear();
        requestListeners.clear();
        cookieStore.clear();
        authenticationStore.clearAuthentications();
        authenticationStore.clearAuthenticationResults();
        decoderFactories.clear();

        super.doStop();

        LOG.info("Stopped {}", this);
    }

    public List<Request.Listener> getRequestListeners()
    {
        return requestListeners;
    }

    public CookieStore getCookieStore()
    {
        return cookieStore;
    }

    public AuthenticationStore getAuthenticationStore()
    {
        return authenticationStore;
    }

    public Set<ContentDecoder.Factory> getContentDecoderFactories()
    {
        return decoderFactories;
    }

    public Future<ContentResponse> GET(String uri)
    {
        return GET(URI.create(uri));
    }

    public Future<ContentResponse> GET(URI uri)
    {
        return newRequest(uri).send();
    }

    public Request POST(String uri)
    {
        return POST(URI.create(uri));
    }

    public Request POST(URI uri)
    {
        return newRequest(uri).method(HttpMethod.POST);
    }

    public Request newRequest(String host, int port)
    {
        return newRequest(URI.create(address("http", host, port)));
    }

    public Request newRequest(String uri)
    {
        return newRequest(URI.create(uri));
    }

    public Request newRequest(URI uri)
    {
        return new HttpRequest(this, uri);
    }

    protected Request copyRequest(Request oldRequest, String newURI)
    {
        Request newRequest = new HttpRequest(this, oldRequest.getConversationID(), URI.create(newURI));
        newRequest.method(oldRequest.getMethod())
                .version(oldRequest.getVersion())
                .content(oldRequest.getContent());
        for (HttpFields.Field header : oldRequest.getHeaders())
        {
            // We have a new URI, so skip the host header if present
            if (HttpHeader.HOST == header.getHeader())
                continue;

            newRequest.header(header.getName(), header.getValue());
        }
        return newRequest;
    }

    private String address(String scheme, String host, int port)
    {
        return scheme + "://" + host + ":" + port;
    }

    public Destination getDestination(String scheme, String host, int port)
    {
        return provideDestination(scheme, host, port);
    }

    protected HttpDestination provideDestination(String scheme, String host, int port)
    {
        String address = address(scheme, host, port);
        HttpDestination destination = destinations.get(address);
        if (destination == null)
        {
            destination = new HttpDestination(this, scheme, host, port);
            if (isRunning())
            {
                HttpDestination existing = destinations.putIfAbsent(address, destination);
                if (existing != null)
                    destination = existing;
                else
                    LOG.debug("Created {}", destination);
                if (!isRunning())
                    destinations.remove(address);
            }

        }
        return destination;
    }

    public List<Destination> getDestinations()
    {
        return new ArrayList<Destination>(destinations.values());
    }

    protected void send(final Request request, List<Response.ResponseListener> listeners)
    {
        String scheme = request.getScheme().toLowerCase();
        if (!Arrays.asList("http", "https").contains(scheme))
            throw new IllegalArgumentException("Invalid protocol " + scheme);

        int port = request.getPort();
        if (port < 0)
            port = "https".equals(scheme) ? 443 : 80;

        for (Response.ResponseListener listener : listeners)
            if (listener instanceof Schedulable)
                ((Schedulable)listener).schedule(scheduler);

        HttpDestination destination = provideDestination(scheme, request.getHost(), port);
        destination.send(request, listeners);
    }

    protected void newConnection(HttpDestination destination, Callback<Connection> callback)
    {
        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            SocketAddress bindAddress = getBindAddress();
            if (bindAddress != null)
                channel.bind(bindAddress);
            configure(channel);
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(destination.getHost(), destination.getPort()));

            Future<Connection> result = new ConnectionCallback(destination, callback);
            selectorManager.connect(channel, result);
        }
        catch (IOException x)
        {
            if (channel != null)
                close(channel);
            callback.failed(null, x);
        }
    }

    protected void configure(SocketChannel channel) throws SocketException
    {
        channel.socket().setTcpNoDelay(isTCPNoDelay());
    }

    private void close(SocketChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException x)
        {
            LOG.ignore(x);
        }
    }

    protected HttpConversation getConversation(long id, boolean create)
    {
        HttpConversation conversation = conversations.get(id);
        if (conversation == null && create)
        {
            conversation = new HttpConversation(this, id);
            HttpConversation existing = conversations.putIfAbsent(id, conversation);
            if (existing != null)
                conversation = existing;
            else
                LOG.debug("{} created", conversation);
        }
        return conversation;
    }

    protected void removeConversation(HttpConversation conversation)
    {
        conversations.remove(conversation.id());
        LOG.debug("{} removed", conversation);
    }

    protected List<ProtocolHandler> getProtocolHandlers()
    {
        return handlers;
    }

    protected ProtocolHandler findProtocolHandler(Request request, Response response)
    {
        for (ProtocolHandler handler : getProtocolHandlers())
        {
            if (handler.accept(request, response))
                return handler;
        }
        return null;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(long connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    /**
     * @return the address to bind socket channels to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind socket channels to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public String getUserAgent()
    {
        return agent;
    }

    public void setUserAgent(String agent)
    {
        this.agent = agent;
    }

    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    public void setFollowRedirects(boolean follow)
    {
        this.followRedirects = follow;
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public void setExecutor(Executor executor)
    {
        this.executor = executor;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler)
    {
        this.scheduler = scheduler;
    }

    public SelectorManager getSelectorManager()
    {
        return selectorManager;
    }

    public int getMaxConnectionsPerAddress()
    {
        return maxConnectionsPerAddress;
    }

    public void setMaxConnectionsPerAddress(int maxConnectionsPerAddress)
    {
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

    public int getMaxQueueSizePerAddress()
    {
        return maxQueueSizePerAddress;
    }

    public void setMaxQueueSizePerAddress(int maxQueueSizePerAddress)
    {
        this.maxQueueSizePerAddress = maxQueueSizePerAddress;
    }

    public int getRequestBufferSize()
    {
        return requestBufferSize;
    }

    public void setRequestBufferSize(int requestBufferSize)
    {
        this.requestBufferSize = requestBufferSize;
    }

    public int getResponseBufferSize()
    {
        return responseBufferSize;
    }

    public void setResponseBufferSize(int responseBufferSize)
    {
        this.responseBufferSize = responseBufferSize;
    }

    public int getMaxRedirects()
    {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects)
    {
        this.maxRedirects = maxRedirects;
    }

    public boolean isTCPNoDelay()
    {
        return tcpNoDelay;
    }

    public void setTCPNoDelay(boolean tcpNoDelay)
    {
        this.tcpNoDelay = tcpNoDelay;
    }

    /**
     * @return true to dispatch I/O operations in a different thread, false to execute them in the selector thread
     * @see #setDispatchIO(boolean)
     */
    public boolean isDispatchIO()
    {
        return dispatchIO;
    }

    /**
     * Whether to dispatch I/O operations from the selector thread to a different thread.
     * <p />
     * This implementation never blocks on I/O operation, but invokes application callbacks that may
     * take time to execute or block on other I/O.
     * If application callbacks are known to take time or block on I/O, then parameter {@code dispatchIO}
     * must be set to true.
     * If application callbacks are known to be quick and never block on I/O, then parameter {@code dispatchIO}
     * may be set to false.
     *
     * @param dispatchIO true to dispatch I/O operations in a different thread, false to execute them in the selector thread
     */
    public void setDispatchIO(boolean dispatchIO)
    {
        this.dispatchIO = dispatchIO;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        dump(out, indent, getBeans(), destinations.values());
    }

    protected class ClientSelectorManager extends SelectorManager
    {
        public ClientSelectorManager(Executor executor, Scheduler scheduler)
        {
            this(executor, scheduler, 1);
        }

        public ClientSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key)
        {
            return new SelectChannelEndPoint(channel, selector, key, getScheduler(), getIdleTimeout());
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment) throws IOException
        {
            ConnectionCallback callback = (ConnectionCallback)attachment;
            HttpDestination destination = callback.destination;

            SslContextFactory sslContextFactory = getSslContextFactory();
            if ("https".equals(destination.getScheme()))
            {
                if (sslContextFactory == null)
                {
                    IOException failure = new ConnectException("Missing " + SslContextFactory.class.getSimpleName() + " for " + destination.getScheme() + " requests");
                    callback.failed(null, failure);
                    throw failure;
                }
                else
                {
                    SSLEngine engine = sslContextFactory.newSSLEngine(endPoint.getRemoteAddress());
                    engine.setUseClientMode(true);

                    SslConnection sslConnection = new SslConnection(getByteBufferPool(), getExecutor(), endPoint, engine);
                    // TODO: configureConnection => implies we should use SslConnectionFactory to do it

                    EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
                    HttpConnection connection = new HttpConnection(HttpClient.this, appEndPoint, destination);
                    // TODO: configureConnection, see above

                    appEndPoint.setConnection(connection);
                    callback.callback.completed(connection);

                    return sslConnection;
                }
            }
            else
            {
                HttpConnection connection = new HttpConnection(HttpClient.this, endPoint, destination);
                // TODO: configureConnection, see above
                callback.callback.completed(connection);
                return connection;
            }
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            ConnectionCallback callback = (ConnectionCallback)attachment;
            callback.callback.failed(null, ex);
        }
    }

    private class ConnectionCallback extends FutureCallback<Connection>
    {
        private final HttpDestination destination;
        private final Callback<Connection> callback;

        private ConnectionCallback(HttpDestination destination, Callback<Connection> callback)
        {
            this.destination = destination;
            this.callback = callback;
        }
    }
}
