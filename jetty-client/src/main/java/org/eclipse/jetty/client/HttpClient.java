//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.Sweeper;
import org.eclipse.jetty.util.thread.ThreadPool;

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
 * HttpClient httpClient = new HttpClient();
 * httpClient.start();
 *
 * // One liner:
 * httpClient.GET("http://localhost:8080/").getStatus();
 *
 * // Building a request with a timeout
 * ContentResponse response = httpClient.newRequest("http://localhost:8080")
 *         .timeout(5, TimeUnit.SECONDS)
 *         .send();
 * int status = response.status();
 *
 * // Asynchronously
 * httpClient.newRequest("http://localhost:8080").send(new Response.CompleteListener()
 * {
 *     &#64;Override
 *     public void onComplete(Result result)
 *     {
 *         ...
 *     }
 * });
 * </pre>
 */
@ManagedObject("The HTTP client")
public class HttpClient extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(HttpClient.class);

    private final ConcurrentMap<Origin, HttpDestination> destinations = new ConcurrentHashMap<>();
    private final ProtocolHandlers handlers = new ProtocolHandlers();
    private final List<Request.Listener> requestListeners = new ArrayList<>();
    private final Set<ContentDecoder.Factory> decoderFactories = new ContentDecoderFactorySet();
    private final ProxyConfiguration proxyConfig = new ProxyConfiguration();
    private final HttpClientTransport transport;
    private final SslContextFactory sslContextFactory;
    private AuthenticationStore authenticationStore = new HttpAuthenticationStore();
    private CookieManager cookieManager;
    private CookieStore cookieStore;
    private Executor executor;
    private ByteBufferPool byteBufferPool;
    private Scheduler scheduler;
    private SocketAddressResolver resolver;
    private HttpField agentField = new HttpField(HttpHeader.USER_AGENT, "Jetty/" + Jetty.VERSION);
    private boolean followRedirects = true;
    private int maxConnectionsPerDestination = 64;
    private int maxRequestsQueuedPerDestination = 1024;
    private int requestBufferSize = 4096;
    private int responseBufferSize = 16384;
    private int maxRedirects = 8;
    private SocketAddress bindAddress;
    private long connectTimeout = 15000;
    private long addressResolutionTimeout = 15000;
    private long idleTimeout;
    private boolean tcpNoDelay = true;
    private boolean strictEventOrdering = false;
    private HttpField encodingField;
    private long destinationIdleTimeout;
    private boolean connectBlocking = false;
    private String name = getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    private HttpCompliance httpCompliance = HttpCompliance.RFC7230;
    private String defaultRequestContentType = "application/octet-stream";
    private Sweeper destinationIdleTimeoutSweeper;

    /**
     * Creates a {@link HttpClient} instance that can perform requests to non-TLS destinations only
     * (that is, requests with the "http" scheme only, and not "https").
     *
     * @see #HttpClient(SslContextFactory) to perform requests to TLS destinations.
     */
    public HttpClient()
    {
        this(new HttpClientTransportOverHTTP(), null);
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
        this(new HttpClientTransportOverHTTP(), sslContextFactory);
    }

    /**
     * Creates a {@link HttpClient} instance that can perform requests to non-TLS destinations only
     * (that is, requests with the "http" scheme only, and not "https").
     *
     * @param transport the {@link HttpClientTransport}
     * @see #HttpClient(HttpClientTransport, SslContextFactory)  to perform requests to TLS destinations.
     */
    public HttpClient(HttpClientTransport transport)
    {
        this(transport, null);
    }

    public HttpClient(HttpClientTransport transport, SslContextFactory sslContextFactory)
    {
        this.transport = transport;
        addBean(transport);
        this.sslContextFactory = sslContextFactory;
        addBean(sslContextFactory);
        addBean(handlers);
        addBean(decoderFactories);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("requestListeners", requestListeners));
    }

    public HttpClientTransport getTransport()
    {
        return transport;
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
        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(name);
            setExecutor(threadPool);
        }

        if (byteBufferPool == null)
            setByteBufferPool(new MappedByteBufferPool(2048,
                executor instanceof ThreadPool.SizedThreadPool
                    ? ((ThreadPool.SizedThreadPool)executor).getMaxThreads() / 2
                    : ProcessorUtils.availableProcessors() * 2));

        if (scheduler == null)
            setScheduler(new ScheduledExecutorScheduler(name + "-scheduler", false));

        destinationIdleTimeoutSweeper = new Sweeper(scheduler, 1000L);
        // Bind the start of the destinationIdleTimeoutSweeper to the scheduler's start as we cannot add the former as a bean
        // because HttpDestination.doStart() expects to find a different sweeper by calling getBean() on the HttpClient to be
        // used for connection pool sweeping. That sweeper cannot be used for destinationIdleTimeout sweeping, so we need to
        // maintain a second one on the client that isn't a bean and is dedicated to that task.
        scheduler.addLifeCycleListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStarted(LifeCycle event)
            {
                LifeCycle.start(destinationIdleTimeoutSweeper);
                scheduler.removeLifeCycleListener(this);
            }
        });

        if (resolver == null)
            setSocketAddressResolver(new SocketAddressResolver.Async(executor, scheduler, getAddressResolutionTimeout()));

        handlers.put(new ContinueProtocolHandler());
        handlers.put(new RedirectProtocolHandler(this));
        handlers.put(new WWWAuthenticationProtocolHandler(this));
        handlers.put(new ProxyAuthenticationProtocolHandler(this));

        decoderFactories.add(new GZIPContentDecoder.Factory(byteBufferPool));

        cookieManager = newCookieManager();
        cookieStore = cookieManager.getCookieStore();

        transport.setHttpClient(this);
        super.doStart();
    }

    private CookieManager newCookieManager()
    {
        return new CookieManager(getCookieStore(), CookiePolicy.ACCEPT_ALL);
    }

    @Override
    protected void doStop() throws Exception
    {
        LifeCycle.stop(destinationIdleTimeoutSweeper);

        decoderFactories.clear();
        handlers.clear();

        for (HttpDestination destination : destinations.values())
        {
            destination.close();
        }
        destinations.clear();

        requestListeners.clear();
        authenticationStore.clearAuthentications();
        authenticationStore.clearAuthenticationResults();

        super.doStop();
    }

    /**
     * Returns a <em>non</em> thread-safe list of {@link org.eclipse.jetty.client.api.Request.Listener}s that can be modified before
     * performing requests.
     *
     * @return a list of {@link org.eclipse.jetty.client.api.Request.Listener} that can be used to add and remove listeners
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
     * @param cookieStore the cookie store associated with this instance
     */
    public void setCookieStore(CookieStore cookieStore)
    {
        this.cookieStore = Objects.requireNonNull(cookieStore);
        this.cookieManager = newCookieManager();
    }

    /**
     * Keep this method package-private because its interface is so ugly
     * that we really don't want to expose it more than strictly needed.
     *
     * @return the cookie manager
     */
    CookieManager getCookieManager()
    {
        return cookieManager;
    }

    Sweeper getDestinationIdleTimeoutSweeper()
    {
        return destinationIdleTimeoutSweeper;
    }

    /**
     * @return the authentication store associated with this instance
     */
    public AuthenticationStore getAuthenticationStore()
    {
        return authenticationStore;
    }

    /**
     * @param authenticationStore the authentication store associated with this instance
     */
    public void setAuthenticationStore(AuthenticationStore authenticationStore)
    {
        this.authenticationStore = authenticationStore;
    }

    /**
     * Returns a <em>non</em> thread-safe set of {@link ContentDecoder.Factory}s that can be modified before
     * performing requests.
     *
     * @return a set of {@link ContentDecoder.Factory} that can be used to add and remove content decoder factories
     */
    public Set<ContentDecoder.Factory> getContentDecoderFactories()
    {
        return decoderFactories;
    }

    // @checkstyle-disable-check : MethodNameCheck

    /**
     * Performs a GET request to the specified URI.
     *
     * @param uri the URI to GET
     * @return the {@link ContentResponse} for the request
     * @throws InterruptedException if send threading has been interrupted
     * @throws ExecutionException the execution failed
     * @throws TimeoutException the send timed out
     * @see #GET(URI)
     */
    public ContentResponse GET(String uri) throws InterruptedException, ExecutionException, TimeoutException
    {
        return GET(URI.create(uri));
    }

    /**
     * Performs a GET request to the specified URI.
     *
     * @param uri the URI to GET
     * @return the {@link ContentResponse} for the request
     * @throws InterruptedException if send threading has been interrupted
     * @throws ExecutionException the execution failed
     * @throws TimeoutException the send timed out
     * @see #newRequest(URI)
     */
    public ContentResponse GET(URI uri) throws InterruptedException, ExecutionException, TimeoutException
    {
        return newRequest(uri).send();
    }

    /**
     * Performs a POST request to the specified URI with the given form parameters.
     *
     * @param uri the URI to POST
     * @param fields the fields composing the form name/value pairs
     * @return the {@link ContentResponse} for the request
     * @throws InterruptedException if send threading has been interrupted
     * @throws ExecutionException the execution failed
     * @throws TimeoutException the send timed out
     */
    public ContentResponse FORM(String uri, Fields fields) throws InterruptedException, ExecutionException, TimeoutException
    {
        return FORM(URI.create(uri), fields);
    }

    /**
     * Performs a POST request to the specified URI with the given form parameters.
     *
     * @param uri the URI to POST
     * @param fields the fields composing the form name/value pairs
     * @return the {@link ContentResponse} for the request
     * @throws InterruptedException if send threading has been interrupted
     * @throws ExecutionException the execution failed
     * @throws TimeoutException the send timed out
     */
    public ContentResponse FORM(URI uri, Fields fields) throws InterruptedException, ExecutionException, TimeoutException
    {
        return POST(uri).content(new FormContentProvider(fields)).send();
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
        return newRequest(new Origin("http", host, port).asString());
    }

    /**
     * Creates a new request with the specified absolute URI in string format.
     *
     * @param uri the request absolute URI
     * @return the request just created
     */
    public Request newRequest(String uri)
    {
        return newRequest(URI.create(uri));
    }

    /**
     * Creates a new request with the specified absolute URI.
     *
     * @param uri the request absolute URI
     * @return the request just created
     */
    public Request newRequest(URI uri)
    {
        return newHttpRequest(newConversation(), uri);
    }

    protected Request copyRequest(HttpRequest oldRequest, URI newURI)
    {
        Request newRequest = newHttpRequest(oldRequest.getConversation(), newURI);
        newRequest.method(oldRequest.getMethod())
            .version(oldRequest.getVersion())
            .content(oldRequest.getContent())
            .idleTimeout(oldRequest.getIdleTimeout(), TimeUnit.MILLISECONDS)
            .timeout(oldRequest.getTimeout(), TimeUnit.MILLISECONDS)
            .followRedirects(oldRequest.isFollowRedirects());
        for (HttpField field : oldRequest.getHeaders())
        {
            HttpHeader header = field.getHeader();
            // We have a new URI, so skip the host header if present.
            if (HttpHeader.HOST == header)
                continue;

            // Remove expectation headers.
            if (HttpHeader.EXPECT == header)
                continue;

            // Remove cookies.
            if (HttpHeader.COOKIE == header)
                continue;

            // Remove authorization headers.
            if (HttpHeader.AUTHORIZATION == header ||
                HttpHeader.PROXY_AUTHORIZATION == header)
                continue;

            String name = field.getName();
            String value = field.getValue();
            if (!newRequest.getHeaders().contains(name, value))
                newRequest.header(name, value);
        }
        return newRequest;
    }

    protected HttpRequest newHttpRequest(HttpConversation conversation, URI uri)
    {
        return new HttpRequest(this, conversation, checkHost(uri));
    }

    /**
     * <p>Checks {@code uri} for the host to be non-null host.</p>
     * <p>URIs built from strings that have an internationalized domain name (IDN)
     * are parsed without errors, but {@code uri.getHost()} returns null.</p>
     *
     * @param uri the URI to check for non-null host
     * @return the same {@code uri} if the host is non-null
     * @throws IllegalArgumentException if the host is null
     */
    private URI checkHost(URI uri)
    {
        if (uri.getHost() == null)
            throw new IllegalArgumentException(String.format("Invalid URI host: null (authority: %s)", uri.getRawAuthority()));
        return uri;
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
        return destinationFor(scheme, host, port);
    }

    protected HttpDestination destinationFor(String scheme, String host, int port)
    {
        return resolveDestination(scheme, host, port, null);
    }

    protected HttpDestination resolveDestination(String scheme, String host, int port, Object tag)
    {
        Origin origin = createOrigin(scheme, host, port, tag);
        return resolveDestination(origin);
    }

    protected Origin createOrigin(String scheme, String host, int port, Object tag)
    {
        if (!HttpScheme.HTTP.is(scheme) && !HttpScheme.HTTPS.is(scheme) &&
            !HttpScheme.WS.is(scheme) && !HttpScheme.WSS.is(scheme))
            throw new IllegalArgumentException("Invalid protocol " + scheme);
        scheme = scheme.toLowerCase(Locale.ENGLISH);
        host = host.toLowerCase(Locale.ENGLISH);
        port = normalizePort(scheme, port);
        return new Origin(scheme, host, port, tag);
    }

    /**
     * <p>Returns, creating it if absent, the destination with the given origin.</p>
     *
     * @param origin the origin that identifies the destination
     * @return the destination for the given origin
     */
    public HttpDestination resolveDestination(Origin origin)
    {
        return destinations.compute(origin, (k, v) ->
        {
            if (v == null || v.stale())
            {
                HttpDestination newDestination = getTransport().newHttpDestination(k);
                addManaged(newDestination);
                if (LOG.isDebugEnabled())
                    LOG.debug("Created {}; existing: '{}'", newDestination, v);
                return newDestination;
            }
            return v;
        });
    }

    protected boolean removeDestination(HttpDestination destination)
    {
        boolean removed = destinations.remove(destination.getOrigin(), destination);
        removeBean(destination);
        if (LOG.isDebugEnabled())
            LOG.debug("Removed {}; result: {}", destination, removed);
        return removed;
    }

    /**
     * @return the list of destinations known to this {@link HttpClient}.
     */
    public List<Destination> getDestinations()
    {
        return new ArrayList<>(destinations.values());
    }

    protected void send(final HttpRequest request, List<Response.ResponseListener> listeners)
    {
        HttpDestination destination = resolveDestination(request.getScheme(), request.getHost(), request.getPort(), request.getTag());
        destination.send(request, listeners);
    }

    protected void newConnection(final HttpDestination destination, final Promise<Connection> promise)
    {
        Origin.Address address = destination.getConnectAddress();
        resolver.resolve(address.getHost(), address.getPort(), new Promise<List<InetSocketAddress>>()
        {
            @Override
            public void succeeded(List<InetSocketAddress> socketAddresses)
            {
                // Multiple threads may access the map, especially with DEBUG logging enabled.
                Map<String, Object> context = new ConcurrentHashMap<>();
                context.put(ClientConnectionFactory.CONNECTOR_CONTEXT_KEY, HttpClient.this);
                context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, destination);
                connect(socketAddresses, 0, context);
            }

            @Override
            public void failed(Throwable x)
            {
                promise.failed(x);
            }

            private void connect(List<InetSocketAddress> socketAddresses, int index, Map<String, Object> context)
            {
                context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, new Promise.Wrapper<Connection>(promise)
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        int nextIndex = index + 1;
                        if (nextIndex == socketAddresses.size())
                            super.failed(x);
                        else
                            connect(socketAddresses, nextIndex, context);
                    }
                });
                transport.connect(socketAddresses.get(index), context);
            }
        });
    }

    private HttpConversation newConversation()
    {
        return new HttpConversation();
    }

    public ProtocolHandlers getProtocolHandlers()
    {
        return handlers;
    }

    protected ProtocolHandler findProtocolHandler(Request request, Response response)
    {
        return handlers.find(request, response);
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
        if (isStarted())
            LOG.warn("Calling setByteBufferPool() while started is deprecated");
        updateBean(this.byteBufferPool, byteBufferPool);
        this.byteBufferPool = byteBufferPool;
    }

    /**
     * @return the name of this HttpClient
     */
    @ManagedAttribute("The name of this HttpClient")
    public String getName()
    {
        return name;
    }

    /**
     * <p>Sets the name of this HttpClient.</p>
     * <p>The name is also used to generate the JMX ObjectName of this HttpClient
     * and must be set before the registration of the HttpClient MBean in the MBeanServer.</p>
     *
     * @param name the name of this HttpClient
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * @return the max time, in milliseconds, a connection can take to connect to destinations. Zero value means infinite timeout.
     */
    @ManagedAttribute("The timeout, in milliseconds, for connect() operations")
    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the max time, in milliseconds, a connection can take to connect to destinations. Zero value means infinite timeout.
     * @see java.net.Socket#connect(SocketAddress, int)
     */
    public void setConnectTimeout(long connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return the timeout, in milliseconds, for the default {@link SocketAddressResolver} created at startup
     * @see #getSocketAddressResolver()
     */
    public long getAddressResolutionTimeout()
    {
        return addressResolutionTimeout;
    }

    /**
     * <p>Sets the socket address resolution timeout used by the default {@link SocketAddressResolver}
     * created by this {@link HttpClient} at startup.</p>
     * <p>For more fine tuned configuration of socket address resolution, see
     * {@link #setSocketAddressResolver(SocketAddressResolver)}.</p>
     *
     * @param addressResolutionTimeout the timeout, in milliseconds, for the default {@link SocketAddressResolver} created at startup
     * @see #setSocketAddressResolver(SocketAddressResolver)
     */
    public void setAddressResolutionTimeout(long addressResolutionTimeout)
    {
        this.addressResolutionTimeout = addressResolutionTimeout;
    }

    /**
     * @return the max time, in milliseconds, a connection can be idle (that is, without traffic of bytes in either direction)
     */
    @ManagedAttribute("The timeout, in milliseconds, to close idle connections")
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    /**
     * @param idleTimeout the max time, in milliseconds, a connection can be idle (that is, without traffic of bytes in either direction)
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
     * @return the "User-Agent" HTTP field of this {@link HttpClient}
     */
    public HttpField getUserAgentField()
    {
        return agentField;
    }

    /**
     * @param agent the "User-Agent" HTTP header string of this {@link HttpClient}
     */
    public void setUserAgentField(HttpField agent)
    {
        if (agent != null && agent.getHeader() != HttpHeader.USER_AGENT)
            throw new IllegalArgumentException();
        this.agentField = agent;
    }

    /**
     * @return whether this {@link HttpClient} follows HTTP redirects
     * @see Request#isFollowRedirects()
     */
    @ManagedAttribute("Whether HTTP redirects are followed")
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
        if (isStarted())
            LOG.warn("Calling setExecutor() while started is deprecated");
        updateBean(this.executor, executor);
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
        if (isStarted())
            LOG.warn("Calling setScheduler() while started is deprecated");
        updateBean(this.scheduler, scheduler);
        this.scheduler = scheduler;
    }

    /**
     * @return the {@link SocketAddressResolver} of this {@link HttpClient}
     */
    public SocketAddressResolver getSocketAddressResolver()
    {
        return resolver;
    }

    /**
     * @param resolver the {@link SocketAddressResolver} of this {@link HttpClient}
     */
    public void setSocketAddressResolver(SocketAddressResolver resolver)
    {
        if (isStarted())
            LOG.warn("Calling setSocketAddressResolver() while started is deprecated");
        updateBean(this.resolver, resolver);
        this.resolver = resolver;
    }

    /**
     * @return the max number of connections that this {@link HttpClient} opens to {@link Destination}s
     */
    @ManagedAttribute("The max number of connections per each destination")
    public int getMaxConnectionsPerDestination()
    {
        return maxConnectionsPerDestination;
    }

    /**
     * Sets the max number of connections to open to each destinations.
     * <p>
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
    @ManagedAttribute("The max number of requests queued per each destination")
    public int getMaxRequestsQueuedPerDestination()
    {
        return maxRequestsQueuedPerDestination;
    }

    /**
     * Sets the max number of requests that may be queued to a destination.
     * <p>
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
    @ManagedAttribute("The request buffer size")
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
    @ManagedAttribute("The response buffer size")
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
     * @return the max number of HTTP redirects that are followed in a conversation
     * @see #setMaxRedirects(int)
     */
    public int getMaxRedirects()
    {
        return maxRedirects;
    }

    /**
     * @param maxRedirects the max number of HTTP redirects that are followed in a conversation, or -1 for unlimited redirects
     * @see #setFollowRedirects(boolean)
     */
    public void setMaxRedirects(int maxRedirects)
    {
        this.maxRedirects = maxRedirects;
    }

    /**
     * @return whether TCP_NODELAY is enabled
     */
    @ManagedAttribute(value = "Whether the TCP_NODELAY option is enabled", name = "tcpNoDelay")
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
    @Deprecated
    public boolean isDispatchIO()
    {
        // TODO this did default to true, so usage needs to be evaluated.
        return false;
    }

    /**
     * Whether to dispatch I/O operations from the selector thread to a different thread.
     * <p>
     * This implementation never blocks on I/O operation, but invokes application callbacks that may
     * take time to execute or block on other I/O.
     * If application callbacks are known to take time or block on I/O, then parameter {@code dispatchIO}
     * should be set to true.
     * If application callbacks are known to be quick and never block on I/O, then parameter {@code dispatchIO}
     * may be set to false.
     *
     * @param dispatchIO true to dispatch I/O operations in a different thread,
     * false to execute them in the selector thread
     */
    @Deprecated
    public void setDispatchIO(boolean dispatchIO)
    {
    }

    /**
     * Gets the http compliance mode for parsing http responses.
     * The default http compliance level is {@link HttpCompliance#RFC7230} which is the latest HTTP/1.1 specification
     *
     * @return the HttpCompliance instance
     */
    public HttpCompliance getHttpCompliance()
    {
        return httpCompliance;
    }

    /**
     * Sets the http compliance mode for parsing http responses.
     * This affect how weak the {@link HttpParser} parses http responses and which http protocol level is supported
     *
     * @param httpCompliance The compliance level which is used to actually parse http responses
     */
    public void setHttpCompliance(HttpCompliance httpCompliance)
    {
        this.httpCompliance = httpCompliance;
    }

    /**
     * @return whether request events must be strictly ordered
     * @see #setStrictEventOrdering(boolean)
     */
    @ManagedAttribute("Whether request/response events must be strictly ordered")
    public boolean isStrictEventOrdering()
    {
        return strictEventOrdering;
    }

    /**
     * Whether request/response events must be strictly ordered with respect to connection usage.
     * <p>
     * From the point of view of connection usage, the connection can be reused just before the
     * "complete" event notified to {@link org.eclipse.jetty.client.api.Response.CompleteListener}s
     * (but after the "success" event).
     * <p>
     * When a request/response exchange is completing, the destination may have another request
     * queued to be sent to the server.
     * If the connection for that destination is reused for the second request before the "complete"
     * event of the first exchange, it may happen that the "begin" event of the second request
     * happens before the "complete" event of the first exchange.
     * <p>
     * Enforcing strict ordering of events so that a "begin" event of a request can never happen
     * before the "complete" event of the previous exchange comes with the cost of increased
     * connection usage.
     * In case of HTTP redirects and strict event ordering, for example, the redirect request will
     * be forced to open a new connection because it is typically sent from the complete listener
     * when the connection cannot yet be reused.
     * When strict event ordering is not enforced, the redirect request will reuse the already
     * open connection making the system more efficient.
     * <p>
     * The default value for this property is {@code false}.
     *
     * @param strictEventOrdering whether request/response events must be strictly ordered
     */
    public void setStrictEventOrdering(boolean strictEventOrdering)
    {
        this.strictEventOrdering = strictEventOrdering;
    }

    /**
     * @return the delay in ms before idle destinations should be removed
     * @see #setDestinationIdleTimeout(long)
     */
    @ManagedAttribute("The delay in ms before idle destinations are removed, disabled when zero or negative")
    public long getDestinationIdleTimeout()
    {
        return destinationIdleTimeout;
    }

    /**
     * Whether destinations that have no connections (nor active nor idle) should be removed
     * after a certain delay.
     * <p>
     * Applications typically make request to a limited number of destinations so keeping
     * destinations around is not a problem for the memory or the GC.
     * However, for applications that hit millions of different destinations (e.g. a spider
     * bot) it would be useful to be able to remove the old destinations that won't be visited
     * anymore and leave space for new destinations.
     *
     * @param destinationIdleTimeout the delay in ms before idle destinations should be removed
     * @see org.eclipse.jetty.client.DuplexConnectionPool
     */
    public void setDestinationIdleTimeout(long destinationIdleTimeout)
    {
        this.destinationIdleTimeout = destinationIdleTimeout;
    }

    /**
     * @return whether destinations that have no connections should be removed
     * @see #setRemoveIdleDestinations(boolean)
     * @deprecated replaced by {@link #getDestinationIdleTimeout()}
     */
    @Deprecated
    @ManagedAttribute("Whether idle destinations are removed")
    public boolean isRemoveIdleDestinations()
    {
        return destinationIdleTimeout > 0L;
    }

    /**
     * Whether destinations that have no connections (nor active nor idle) should be removed.
     * <p>
     * Applications typically make request to a limited number of destinations so keeping
     * destinations around is not a problem for the memory or the GC.
     * However, for applications that hit millions of different destinations (e.g. a spider
     * bot) it would be useful to be able to remove the old destinations that won't be visited
     * anymore and leave space for new destinations.
     *
     * @param removeIdleDestinations whether destinations that have no connections should be removed
     * @see org.eclipse.jetty.client.DuplexConnectionPool
     * @deprecated replaced by {@link #setDestinationIdleTimeout(long)}, calls the latter with a value of 10000 ms.
     */
    @Deprecated
    public void setRemoveIdleDestinations(boolean removeIdleDestinations)
    {
        setDestinationIdleTimeout(removeIdleDestinations ? 10_000 : 0);
    }

    /**
     * @return whether {@code connect()} operations are performed in blocking mode
     */
    @ManagedAttribute("Whether the connect() operation is blocking")
    public boolean isConnectBlocking()
    {
        return connectBlocking;
    }

    /**
     * <p>Whether {@code connect()} operations are performed in blocking mode.</p>
     * <p>If {@code connect()} are performed in blocking mode, then {@link Socket#connect(SocketAddress, int)}
     * will be used to connect to servers.</p>
     * <p>Otherwise, {@link SocketChannel#connect(SocketAddress)} will be used in non-blocking mode,
     * therefore registering for {@link SelectionKey#OP_CONNECT} and finishing the connect operation
     * when the NIO system emits that event.</p>
     *
     * @param connectBlocking whether {@code connect()} operations are performed in blocking mode
     */
    public void setConnectBlocking(boolean connectBlocking)
    {
        this.connectBlocking = connectBlocking;
    }

    /**
     * @return the default content type for request content
     */
    @ManagedAttribute("The default content type for request content")
    public String getDefaultRequestContentType()
    {
        return defaultRequestContentType;
    }

    /**
     * @param contentType the default content type for request content
     */
    public void setDefaultRequestContentType(String contentType)
    {
        this.defaultRequestContentType = contentType;
    }

    /**
     * @return the forward proxy configuration
     */
    public ProxyConfiguration getProxyConfiguration()
    {
        return proxyConfig;
    }

    protected HttpField getAcceptEncodingField()
    {
        return encodingField;
    }

    /**
     * @param host the host to normalize
     * @return the host itself
     * @deprecated no replacement, do not use it
     */
    @Deprecated
    protected String normalizeHost(String host)
    {
        return host;
    }

    public static int normalizePort(String scheme, int port)
    {
        if (port > 0)
            return port;
        else if (isSchemeSecure(scheme))
            return 443;
        else
            return 80;
    }

    public boolean isDefaultPort(String scheme, int port)
    {
        if (isSchemeSecure(scheme))
            return port == 443;
        else
            return port == 80;
    }

    static boolean isSchemeSecure(String scheme)
    {
        return HttpScheme.HTTPS.is(scheme) || HttpScheme.WSS.is(scheme);
    }

    /**
     * Creates a new {@code SslClientConnectionFactory} wrapping the given connection factory.
     *
     * @param connectionFactory the connection factory to wrap
     * @return a new SslClientConnectionFactory
     * @deprecated use {@link #newSslClientConnectionFactory(SslContextFactory, ClientConnectionFactory)} instead
     */
    @Deprecated
    protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new SslClientConnectionFactory(getSslContextFactory(), getByteBufferPool(), getExecutor(), connectionFactory);
    }

    protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
    {
        if (sslContextFactory == null)
            return newSslClientConnectionFactory(connectionFactory);
        return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory);
    }

    private class ContentDecoderFactorySet implements Set<ContentDecoder.Factory>
    {
        private final Set<ContentDecoder.Factory> set = new HashSet<>();

        @Override
        public boolean add(ContentDecoder.Factory e)
        {
            boolean result = set.add(e);
            invalidate();
            return result;
        }

        @Override
        public boolean addAll(Collection<? extends ContentDecoder.Factory> c)
        {
            boolean result = set.addAll(c);
            invalidate();
            return result;
        }

        @Override
        public boolean remove(Object o)
        {
            boolean result = set.remove(o);
            invalidate();
            return result;
        }

        @Override
        public boolean removeAll(Collection<?> c)
        {
            boolean result = set.removeAll(c);
            invalidate();
            return result;
        }

        @Override
        public boolean retainAll(Collection<?> c)
        {
            boolean result = set.retainAll(c);
            invalidate();
            return result;
        }

        @Override
        public void clear()
        {
            set.clear();
            invalidate();
        }

        @Override
        public int size()
        {
            return set.size();
        }

        @Override
        public boolean isEmpty()
        {
            return set.isEmpty();
        }

        @Override
        public boolean contains(Object o)
        {
            return set.contains(o);
        }

        @Override
        public boolean containsAll(Collection<?> c)
        {
            return set.containsAll(c);
        }

        @Override
        public Iterator<ContentDecoder.Factory> iterator()
        {
            final Iterator<ContentDecoder.Factory> iterator = set.iterator();
            return new Iterator<ContentDecoder.Factory>()
            {
                @Override
                public boolean hasNext()
                {
                    return iterator.hasNext();
                }

                @Override
                public ContentDecoder.Factory next()
                {
                    return iterator.next();
                }

                @Override
                public void remove()
                {
                    iterator.remove();
                    invalidate();
                }
            };
        }

        @Override
        public Object[] toArray()
        {
            return set.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a)
        {
            return set.toArray(a);
        }

        private void invalidate()
        {
            if (set.isEmpty())
            {
                encodingField = null;
            }
            else
            {
                StringBuilder value = new StringBuilder();
                for (Iterator<ContentDecoder.Factory> iterator = set.iterator(); iterator.hasNext(); )
                {
                    ContentDecoder.Factory decoderFactory = iterator.next();
                    value.append(decoderFactory.getEncoding());
                    if (iterator.hasNext())
                        value.append(",");
                }
                encodingField = new HttpField(HttpHeader.ACCEPT_ENCODING, value.toString());
            }
        }
    }
}
