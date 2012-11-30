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
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.jetty.client.api.ProxyConfiguration;
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
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
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
 * client.newRequest("http://localhost:8080").send(new Response.CompleteListener()
 * {
 *     &#64;Override
 *     public void onComplete(Result result)
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
    private volatile int maxConnectionsPerDestination = 8;
    private volatile int maxRequestsQueuedPerDestination = 1024;
    private volatile int requestBufferSize = 4096;
    private volatile int responseBufferSize = 4096;
    private volatile int maxRedirects = 8;
    private volatile SocketAddress bindAddress;
    private volatile long connectTimeout = 15000;
    private volatile long idleTimeout;
    private volatile boolean tcpNoDelay = true;
    private volatile boolean dispatchIO = true;
    private volatile ProxyConfiguration proxyConfig;

    /**
     * Creates a {@link HttpClient} instance that can perform requests to non-TLS destinations only
     * (that is, requests with the "http" scheme only, and not "https").
     *
     * @see #HttpClient(SslContextFactory) to perform requests to TLS destinations.
     */
    public HttpClient()
    {
        this(null);
    }

    /**
     * Creates a {@link HttpClient} instance that can perform requests to non-TLS and TLS destinations
     * (that is, both requests with the "http" scheme and with the "https" scheme).
     *
     * @param sslContextFactory the {@link SslContextFactory} that manages TLS encryption
     * @see #getSslContextFactory()
     */
    public HttpClient(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
    }

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     * @see #HttpClient(SslContextFactory)
     */
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

    /**
     * @return a list of {@link Request.Listener} that can be used to add and remove listeners
     */
    public List<Request.Listener> getRequestListeners()
    {
        return requestListeners;
    }

    /**
     * @return the cookie store associated with this instance
     */
    public CookieStore getCookieStore()
    {
        return cookieStore;
    }

    /**
     * @return the authentication store associated with this instance
     */
    public AuthenticationStore getAuthenticationStore()
    {
        return authenticationStore;
    }

    /**
     * @return a set of {@link ContentDecoder.Factory} that can be used to add and remove content decoder factories
     */
    public Set<ContentDecoder.Factory> getContentDecoderFactories()
    {
        return decoderFactories;
    }

    /**
     * Performs a GET request to the specified URI.
     *
     * @param uri the URI to GET
     * @return a future for a {@link ContentResponse}
     * @see #GET(URI)
     */
    public Future<ContentResponse> GET(String uri)
    {
        return GET(URI.create(uri));
    }

    /**
     * Performs a GET request to the specified URI.
     *
     * @param uri the URI to GET
     * @return a future for a {@link ContentResponse}
     * @see #newRequest(URI)
     */
    public Future<ContentResponse> GET(URI uri)
    {
        return newRequest(uri).send();
    }

    /**
     * Creates a POST request to the specified URI.
     *
     * @param uri the URI to POST to
     * @return the POST request
     * @see #POST(URI)
     */
    public Request POST(String uri)
    {
        return POST(URI.create(uri));
    }

    /**
     * Creates a POST request to the specified URI.
     *
     * @param uri the URI to POST to
     * @return the POST request
     */
    public Request POST(URI uri)
    {
        return newRequest(uri).method(HttpMethod.POST);
    }

    /**
     * Creates a new request with the "http" scheme and the specified host and port
     *
     * @param host the request host
     * @param port the request port
     * @return the request just created
     */
    public Request newRequest(String host, int port)
    {
        return newRequest(URI.create(address("http", host, port)));
    }

    /**
     * Creates a new request with the specified URI.
     *
     * @param uri the URI to request
     * @return the request just created
     */
    public Request newRequest(String uri)
    {
        return newRequest(URI.create(uri));
    }

    /**
     * Creates a new request with the specified URI.
     *
     * @param uri the URI to request
     * @return the request just created
     */
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

    /**
     * Returns a {@link Destination} for the given scheme, host and port.
     * Applications may use {@link Destination}s to create {@link Connection}s
     * that will be outside {@link HttpClient}'s pooling mechanism, to explicitly
     * control the connection lifecycle (in particular their termination with
     * {@link Connection#close()}).
     *
     * @param scheme the destination scheme
     * @param host the destination host
     * @param port the destination port
     * @return the destination
     * @see #getDestinations()
     */
    public Destination getDestination(String scheme, String host, int port)
    {
        return provideDestination(scheme, host, port);
    }

    protected HttpDestination provideDestination(String scheme, String host, int port)
    {
        if (port <= 0)
            port = "https".equalsIgnoreCase(scheme) ? 443 : 80;

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

    /**
     * @return the list of destinations known to this {@link HttpClient}.
     */
    public List<Destination> getDestinations()
    {
        return new ArrayList<Destination>(destinations.values());
    }

    protected void send(final Request request, List<Response.ResponseListener> listeners)
    {
        String scheme = request.getScheme().toLowerCase(Locale.ENGLISH);
        if (!Arrays.asList("http", "https").contains(scheme))
            throw new IllegalArgumentException("Invalid protocol " + scheme);

        for (Response.ResponseListener listener : listeners)
            if (listener instanceof Schedulable)
                ((Schedulable)listener).schedule(scheduler);

        HttpDestination destination = provideDestination(scheme, request.getHost(), request.getPort());
        destination.send(request, listeners);
    }

    protected void newConnection(HttpDestination destination, Promise<Connection> promise)
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
            channel.connect(destination.getConnectAddress());

            Future<Connection> result = new ConnectionCallback(destination, promise);
            selectorManager.connect(channel, result);
        }
        catch (IOException x)
        {
            if (channel != null)
                close(channel);
            promise.failed(x);
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

    /**
     * @return the {@link ByteBufferPool} of this {@link HttpClient}
     */
    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    /**
     * @param byteBufferPool the {@link ByteBufferPool} of this {@link HttpClient}
     */
    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    /**
     * @return the max time a connection can take to connect to destinations
     */
    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the max time a connection can take to connect to destinations
     * @see java.net.Socket#connect(SocketAddress, int)
     */
    public void setConnectTimeout(long connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return the max time a connection can be idle (that is, without traffic of bytes in either direction)
     */
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    /**
     * @param idleTimeout the max time a connection can be idle (that is, without traffic of bytes in either direction)
     */
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
     * @see SocketChannel#bind(SocketAddress)
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    /**
     * @return the "User-Agent" HTTP header string of this {@link HttpClient}
     */
    public String getUserAgent()
    {
        return agent;
    }

    /**
     * @param agent the "User-Agent" HTTP header string of this {@link HttpClient}
     */
    public void setUserAgent(String agent)
    {
        this.agent = agent;
    }

    /**
     * @return whether this {@link HttpClient} follows HTTP redirects
     * @see Request#isFollowRedirects()
     */
    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    /**
     * @param follow whether this {@link HttpClient} follows HTTP redirects
     * @see #setMaxRedirects(int)
     */
    public void setFollowRedirects(boolean follow)
    {
        this.followRedirects = follow;
    }

    /**
     * @return the {@link Executor} of this {@link HttpClient}
     */
    public Executor getExecutor()
    {
        return executor;
    }

    /**
     * @param executor the {@link Executor} of this {@link HttpClient}
     */
    public void setExecutor(Executor executor)
    {
        this.executor = executor;
    }

    /**
     * @return the {@link Scheduler} of this {@link HttpClient}
     */
    public Scheduler getScheduler()
    {
        return scheduler;
    }

    /**
     * @param scheduler the {@link Scheduler} of this {@link HttpClient}
     */
    public void setScheduler(Scheduler scheduler)
    {
        this.scheduler = scheduler;
    }

    protected SelectorManager getSelectorManager()
    {
        return selectorManager;
    }

    /**
     * @return the max number of connections that this {@link HttpClient} opens to {@link Destination}s
     */
    public int getMaxConnectionsPerDestination()
    {
        return maxConnectionsPerDestination;
    }

    /**
     * Sets the max number of connections to open to each destinations.
     * <p />
     * RFC 2616 suggests that 2 connections should be opened per each destination,
     * but browsers commonly open 6.
     * If this {@link HttpClient} is used for load testing, it is common to have only one destination
     * (the server to load test), and it is recommended to set this value to a high value (at least as
     * much as the threads present in the {@link #getExecutor() executor}).
     *
     * @param maxConnectionsPerDestination the max number of connections that this {@link HttpClient} opens to {@link Destination}s
     */
    public void setMaxConnectionsPerDestination(int maxConnectionsPerDestination)
    {
        this.maxConnectionsPerDestination = maxConnectionsPerDestination;
    }

    /**
     * @return the max number of requests that may be queued to a {@link Destination}.
     */
    public int getMaxRequestsQueuedPerDestination()
    {
        return maxRequestsQueuedPerDestination;
    }

    /**
     * Sets the max number of requests that may be queued to a destination.
     * <p />
     * If this {@link HttpClient} performs a high rate of requests to a destination,
     * and all the connections managed by that destination are busy with other requests,
     * then new requests will be queued up in the destination.
     * This parameter controls how many requests can be queued before starting to reject them.
     * If this {@link HttpClient} is used for load testing, it is common to have this parameter
     * set to a high value, although this may impact latency (requests sit in the queue for a long
     * time before being sent).
     *
     * @param maxRequestsQueuedPerDestination the max number of requests that may be queued to a {@link Destination}.
     */
    public void setMaxRequestsQueuedPerDestination(int maxRequestsQueuedPerDestination)
    {
        this.maxRequestsQueuedPerDestination = maxRequestsQueuedPerDestination;
    }

    /**
     * @return the size of the buffer used to write requests
     */
    public int getRequestBufferSize()
    {
        return requestBufferSize;
    }

    /**
     * @param requestBufferSize the size of the buffer used to write requests
     */
    public void setRequestBufferSize(int requestBufferSize)
    {
        this.requestBufferSize = requestBufferSize;
    }

    /**
     * @return the size of the buffer used to read responses
     */
    public int getResponseBufferSize()
    {
        return responseBufferSize;
    }

    /**
     * @param responseBufferSize the size of the buffer used to read responses
     */
    public void setResponseBufferSize(int responseBufferSize)
    {
        this.responseBufferSize = responseBufferSize;
    }

    /**
     * @return the max number of HTTP redirects that are followed
     * @see #setMaxRedirects(int)
     */
    public int getMaxRedirects()
    {
        return maxRedirects;
    }

    /**
     * @param maxRedirects the max number of HTTP redirects that are followed
     * @see #setFollowRedirects(boolean)
     */
    public void setMaxRedirects(int maxRedirects)
    {
        this.maxRedirects = maxRedirects;
    }

    /**
     * @return whether TCP_NODELAY is enabled
     */
    public boolean isTCPNoDelay()
    {
        return tcpNoDelay;
    }

    /**
     * @param tcpNoDelay whether TCP_NODELAY is enabled
     * @see java.net.Socket#setTcpNoDelay(boolean)
     */
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
     * should be set to true.
     * If application callbacks are known to be quick and never block on I/O, then parameter {@code dispatchIO}
     * may be set to false.
     *
     * @param dispatchIO true to dispatch I/O operations in a different thread,
     *                   false to execute them in the selector thread
     */
    public void setDispatchIO(boolean dispatchIO)
    {
        this.dispatchIO = dispatchIO;
    }

    /**
     * @return the forward proxy configuration
     */
    public ProxyConfiguration getProxyConfiguration()
    {
        return proxyConfig;
    }

    /**
     * @param proxyConfig the forward proxy configuration
     */
    public void setProxyConfiguration(ProxyConfiguration proxyConfig)
    {
        this.proxyConfig = proxyConfig;
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
                    callback.failed(failure);
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
                    callback.promise.succeeded(connection);

                    return sslConnection;
                }
            }
            else
            {
                HttpConnection connection = new HttpConnection(HttpClient.this, endPoint, destination);
                // TODO: configureConnection, see above
                callback.promise.succeeded(connection);
                return connection;
            }
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            ConnectionCallback callback = (ConnectionCallback)attachment;
            callback.promise.failed(ex);
        }
    }

    private class ConnectionCallback extends FuturePromise<Connection>
    {
        private final HttpDestination destination;
        private final Promise<Connection> promise;

        private ConnectionCallback(HttpDestination destination, Promise<Connection> promise)
        {
            this.destination = destination;
            this.promise = promise;
        }
    }
}
