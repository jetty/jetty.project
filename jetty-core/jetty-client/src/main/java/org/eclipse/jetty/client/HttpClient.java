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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.internal.HttpAuthenticationStore;
import org.eclipse.jetty.client.internal.HttpConversation;
import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.client.internal.HttpRequest;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBufferPool;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.Sweeper;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>HttpClient provides an efficient, asynchronous, non-blocking implementation
 * to perform HTTP requests to a server through a simple API that offers also blocking semantic.</p>
 * <p>HttpClient provides easy-to-use methods such as {@link #GET(String)} that allow to perform HTTP
 * requests in a one-liner, but also gives the ability to fine tune the configuration of requests via
 * {@link HttpClient#newRequest(URI)}.</p>
 * <p>HttpClient acts as a central configuration point for network parameters (such as idle timeouts)
 * and HTTP parameters (such as whether to follow redirects).</p>
 * <p>HttpClient transparently pools connections to servers, but allows direct control of connections
 * for cases where this is needed.</p>
 * <p>HttpClient also acts as a central configuration point for cookies, via {@link #getCookieStore()}.</p>
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
    public static final String USER_AGENT = "Jetty/" + Jetty.VERSION;
    private static final Logger LOG = LoggerFactory.getLogger(HttpClient.class);

    private final ConcurrentMap<Origin, HttpDestination> destinations = new ConcurrentHashMap<>();
    private final ProtocolHandlers handlers = new ProtocolHandlers();
    private final List<Request.Listener> requestListeners = new ArrayList<>();
    private final ContentDecoder.Factories decoderFactories = new ContentDecoder.Factories();
    private final ProxyConfiguration proxyConfig = new ProxyConfiguration();
    private final HttpClientTransport transport;
    private final ClientConnector connector;
    private AuthenticationStore authenticationStore = new HttpAuthenticationStore();
    private CookieManager cookieManager;
    private CookieStore cookieStore;
    private SocketAddressResolver resolver;
    private HttpField agentField = new HttpField(HttpHeader.USER_AGENT, USER_AGENT);
    private boolean followRedirects = true;
    private int maxConnectionsPerDestination = 64;
    private int maxRequestsQueuedPerDestination = 1024;
    private int requestBufferSize = 4096;
    private int responseBufferSize = 16384;
    private int maxRedirects = 8;
    private long addressResolutionTimeout = 15000;
    private boolean strictEventOrdering = false;
    private long destinationIdleTimeout;
    private String name = getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    private HttpCompliance httpCompliance = HttpCompliance.RFC7230;
    private String defaultRequestContentType = "application/octet-stream";
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;
    private Sweeper destinationSweeper;

    /**
     * Creates a HttpClient instance that can perform HTTP/1.1 requests to non-TLS and TLS destinations.
     */
    public HttpClient()
    {
        this(new HttpClientTransportOverHTTP());
    }

    public HttpClient(HttpClientTransport transport)
    {
        this.transport = Objects.requireNonNull(transport);
        addBean(transport);
        this.connector = ((AbstractHttpClientTransport)transport).getContainedBeans(ClientConnector.class).stream().findFirst().orElseThrow();
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
     * @return the {@link SslContextFactory.Client} that manages TLS encryption
     */
    public SslContextFactory.Client getSslContextFactory()
    {
        return connector.getSslContextFactory();
    }

    /**
     * @param sslContextFactory the {@link SslContextFactory.Client} that manages TLS encryption
     */
    public void setSslContextFactory(SslContextFactory.Client sslContextFactory)
    {
        connector.setSslContextFactory(sslContextFactory);
    }

    @Override
    protected void doStart() throws Exception
    {
        Executor executor = getExecutor();
        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(name);
            executor = threadPool;
            setExecutor(executor);
        }
        int maxBucketSize = executor instanceof ThreadPool.SizedThreadPool
            ? ((ThreadPool.SizedThreadPool)executor).getMaxThreads() / 2
            : ProcessorUtils.availableProcessors() * 2;
        ByteBufferPool byteBufferPool = getByteBufferPool();
        if (byteBufferPool == null)
            setByteBufferPool(new MappedByteBufferPool(2048, maxBucketSize));
        if (getBean(RetainableByteBufferPool.class) == null)
            addBean(new ArrayRetainableByteBufferPool(0, 2048, 65536, maxBucketSize));
        Scheduler scheduler = getScheduler();
        if (scheduler == null)
        {
            scheduler = new ScheduledExecutorScheduler(name + "-scheduler", false);
            setScheduler(scheduler);
        }

        if (resolver == null)
            setSocketAddressResolver(new SocketAddressResolver.Async(getExecutor(), scheduler, getAddressResolutionTimeout()));

        handlers.put(new ContinueProtocolHandler());
        handlers.put(new ProcessingProtocolHandler());
        handlers.put(new EarlyHintsProtocolHandler());
        handlers.put(new RedirectProtocolHandler(this));
        handlers.put(new WWWAuthenticationProtocolHandler(this));
        handlers.put(new ProxyAuthenticationProtocolHandler(this));
        handlers.put(new UpgradeProtocolHandler());

        decoderFactories.put(new GZIPContentDecoder.Factory(byteBufferPool));

        cookieManager = newCookieManager();
        cookieStore = cookieManager.getCookieStore();

        transport.setHttpClient(this);

        super.doStart();

        if (getDestinationIdleTimeout() > 0L)
        {
            destinationSweeper = new Sweeper(scheduler, 1000L);
            destinationSweeper.start();
        }
    }

    private CookieManager newCookieManager()
    {
        return new CookieManager(getCookieStore(), CookiePolicy.ACCEPT_ALL);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (destinationSweeper != null)
        {
            destinationSweeper.stop();
            destinationSweeper = null;
        }

        decoderFactories.clear();
        handlers.clear();
        destinations.clear();
        requestListeners.clear();
        authenticationStore.clearAuthentications();
        authenticationStore.clearAuthenticationResults();

        super.doStop();
    }

    /**
     * Returns a <em>non</em> thread-safe list of {@link Request.Listener}s that can be modified before
     * performing requests.
     *
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
     * @param cookieStore the cookie store associated with this instance
     */
    public void setCookieStore(CookieStore cookieStore)
    {
        if (isStarted())
            throw new IllegalStateException();
        this.cookieStore = Objects.requireNonNull(cookieStore);
        this.cookieManager = newCookieManager();
    }

    public void putCookie(URI uri, HttpField field)
    {
        try
        {
            String value = field.getValue();
            if (value != null)
            {
                Map<String, List<String>> header = new HashMap<>(1);
                header.put(field.getHeader().asString(), List.of(value));
                cookieManager.put(uri, header);
            }
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to store cookies {} from {}", field, uri, x);
        }
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
        if (isStarted())
            throw new IllegalStateException();
        this.authenticationStore = authenticationStore;
    }

    /**
     * Returns a <em>non</em> thread-safe set of {@link ContentDecoder.Factory}s that can be modified before
     * performing requests.
     *
     * @return a set of {@link ContentDecoder.Factory} that can be used to add and remove content decoder factories
     */
    public ContentDecoder.Factories getContentDecoderFactories()
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
        return POST(uri).body(new FormRequestContent(fields)).send();
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

    // @checkstyle-enable-check : MethodNameCheck

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

    protected Request copyRequest(Request oldRequest, URI newURI)
    {
        HttpRequest newRequest = newHttpRequest(((HttpRequest)oldRequest).getConversation(), newURI);
        newRequest.method(oldRequest.getMethod())
            .version(oldRequest.getVersion())
            .body(oldRequest.getBody())
            .idleTimeout(oldRequest.getIdleTimeout(), TimeUnit.MILLISECONDS)
            .timeout(oldRequest.getTimeout(), TimeUnit.MILLISECONDS)
            .followRedirects(oldRequest.isFollowRedirects())
            .tag(oldRequest.getTag());
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

            if (!newRequest.getHeaders().contains(field))
                newRequest.addHeader(field);
        }
        return newRequest;
    }

    private HttpRequest newHttpRequest(HttpConversation conversation, URI uri)
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

    public Destination resolveDestination(Request request)
    {
        HttpClientTransport transport = getTransport();
        Origin origin = transport.newOrigin(request);
        Destination destination = resolveDestination(origin);
        if (LOG.isDebugEnabled())
            LOG.debug("Resolved {} for {}", destination, request);
        return destination;
    }

    public Origin createOrigin(Request request, Origin.Protocol protocol)
    {
        String scheme = request.getScheme();
        if (!HttpScheme.HTTP.is(scheme) && !HttpScheme.HTTPS.is(scheme) &&
            !HttpScheme.WS.is(scheme) && !HttpScheme.WSS.is(scheme))
            throw new IllegalArgumentException("Invalid protocol " + scheme);
        scheme = scheme.toLowerCase(Locale.ENGLISH);
        String host = request.getHost();
        host = host.toLowerCase(Locale.ENGLISH);
        int port = request.getPort();
        port = normalizePort(scheme, port);
        return new Origin(scheme, host, port, request.getTag(), protocol);
    }

    /**
     * <p>Returns, creating it if absent, the destination with the given origin.</p>
     *
     * @param origin the origin that identifies the destination
     * @return the destination for the given origin
     */
    public Destination resolveDestination(Origin origin)
    {
        return destinations.compute(origin, (k, v) ->
        {
            if (v == null || v.stale())
            {
                HttpDestination newDestination = (HttpDestination)getTransport().newDestination(k);
                // Start the destination before it's published to other threads.
                addManaged(newDestination);
                if (destinationSweeper != null)
                    destinationSweeper.offer(newDestination);
                if (LOG.isDebugEnabled())
                    LOG.debug("Created {}; existing: '{}'", newDestination, v);
                return newDestination;
            }
            return v;
        });
    }

    public boolean removeDestination(Destination destination)
    {
        HttpDestination httpDestination = (HttpDestination)destination;
        boolean removed = destinations.remove(destination.getOrigin(), httpDestination);
        removeBean(destination);
        if (destinationSweeper != null)
            destinationSweeper.remove(httpDestination);
        if (LOG.isDebugEnabled())
            LOG.debug("Removed {}; result: {}", destination, removed);
        return removed;
    }

    /**
     * @return the list of destinations known to this HttpClient.
     */
    public List<Destination> getDestinations()
    {
        return new ArrayList<>(destinations.values());
    }

    public void newConnection(Destination destination, Promise<Connection> promise)
    {
        // Multiple threads may access the map, especially with DEBUG logging enabled.
        Map<String, Object> context = new ConcurrentHashMap<>();
        context.put(ClientConnectionFactory.CLIENT_CONTEXT_KEY, HttpClient.this);
        context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, destination);
        Origin.Protocol protocol = destination.getOrigin().getProtocol();
        List<String> protocols = protocol != null ? protocol.getProtocols() : List.of("http/1.1");
        context.put(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY, protocols);

        ProxyConfiguration.Proxy proxy = destination.getProxy();
        Origin.Address address = proxy == null ? destination.getOrigin().getAddress() : proxy.getAddress();
        getSocketAddressResolver().resolve(address.getHost(), address.getPort(), new Promise<>()
        {
            @Override
            public void succeeded(List<InetSocketAddress> socketAddresses)
            {
                connect(socketAddresses, 0, context);
            }

            @Override
            public void failed(Throwable x)
            {
                promise.failed(x);
            }

            private void connect(List<InetSocketAddress> socketAddresses, int index, Map<String, Object> context)
            {
                context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, new Promise.Wrapper<>(promise)
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
                transport.connect((SocketAddress)socketAddresses.get(index), context);
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

    public ProtocolHandler findProtocolHandler(Request request, Response response)
    {
        return handlers.find(request, response);
    }

    /**
     * @return the {@link ByteBufferPool} of this HttpClient
     */
    public ByteBufferPool getByteBufferPool()
    {
        return connector.getByteBufferPool();
    }

    /**
     * @param byteBufferPool the {@link ByteBufferPool} of this HttpClient
     */
    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        connector.setByteBufferPool(byteBufferPool);
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
        return connector.getConnectTimeout().toMillis();
    }

    /**
     * @param connectTimeout the max time, in milliseconds, a connection can take to connect to destinations. Zero value means infinite timeout.
     * @see java.net.Socket#connect(SocketAddress, int)
     */
    public void setConnectTimeout(long connectTimeout)
    {
        connector.setConnectTimeout(Duration.ofMillis(connectTimeout));
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
     * created by this HttpClient at startup.</p>
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
        return connector.getIdleTimeout().toMillis();
    }

    /**
     * @param idleTimeout the max time, in milliseconds, a connection can be idle (that is, without traffic of bytes in either direction)
     */
    public void setIdleTimeout(long idleTimeout)
    {
        connector.setIdleTimeout(Duration.ofMillis(idleTimeout));
    }

    /**
     * @return the address to bind socket channels to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return connector.getBindAddress();
    }

    /**
     * @param bindAddress the address to bind socket channels to
     * @see #getBindAddress()
     * @see SocketChannel#bind(SocketAddress)
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        connector.setBindAddress(bindAddress);
    }

    /**
     * @return the "User-Agent" HTTP field of this HttpClient
     */
    public HttpField getUserAgentField()
    {
        return agentField;
    }

    /**
     * @param agent the "User-Agent" HTTP header string of this HttpClient
     */
    public void setUserAgentField(HttpField agent)
    {
        if (agent != null && agent.getHeader() != HttpHeader.USER_AGENT)
            throw new IllegalArgumentException();
        this.agentField = agent;
    }

    /**
     * @return whether this HttpClient follows HTTP redirects
     * @see Request#isFollowRedirects()
     */
    @ManagedAttribute("Whether HTTP redirects are followed")
    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    /**
     * @param follow whether this HttpClient follows HTTP redirects
     * @see #setMaxRedirects(int)
     */
    public void setFollowRedirects(boolean follow)
    {
        this.followRedirects = follow;
    }

    /**
     * @return the {@link Executor} of this HttpClient
     */
    public Executor getExecutor()
    {
        return connector.getExecutor();
    }

    /**
     * @param executor the {@link Executor} of this HttpClient
     */
    public void setExecutor(Executor executor)
    {
        connector.setExecutor(executor);
    }

    /**
     * @return the {@link Scheduler} of this HttpClient
     */
    public Scheduler getScheduler()
    {
        return connector.getScheduler();
    }

    /**
     * @param scheduler the {@link Scheduler} of this HttpClient
     */
    public void setScheduler(Scheduler scheduler)
    {
        connector.setScheduler(scheduler);
    }

    /**
     * @return the {@link SocketAddressResolver} of this HttpClient
     */
    public SocketAddressResolver getSocketAddressResolver()
    {
        return resolver;
    }

    /**
     * @param resolver the {@link SocketAddressResolver} of this HttpClient
     */
    public void setSocketAddressResolver(SocketAddressResolver resolver)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.resolver, resolver);
        this.resolver = resolver;
    }

    /**
     * @return the max number of connections that this HttpClient opens to {@link Destination}s
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
     * If this HttpClient is used for load testing, it is common to have only one destination
     * (the server to load test), and it is recommended to set this value to a high value (at least as
     * much as the threads present in the {@link #getExecutor() executor}).
     *
     * @param maxConnectionsPerDestination the max number of connections that this HttpClient opens to {@link Destination}s
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
     * If this HttpClient performs a high rate of requests to a destination,
     * and all the connections managed by that destination are busy with other requests,
     * then new requests will be queued up in the destination.
     * This parameter controls how many requests can be queued before starting to reject them.
     * If this HttpClient is used for load testing, it is common to have this parameter
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
     * @return the size of the buffer (in bytes) used to write requests
     */
    @ManagedAttribute("The request buffer size in bytes")
    public int getRequestBufferSize()
    {
        return requestBufferSize;
    }

    /**
     * @param requestBufferSize the size of the buffer (in bytes) used to write requests
     */
    public void setRequestBufferSize(int requestBufferSize)
    {
        this.requestBufferSize = requestBufferSize;
    }

    /**
     * @return the size of the buffer (in bytes) used to read responses
     */
    @ManagedAttribute("The response buffer size in bytes")
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
     * "complete" event notified to {@link Response.CompleteListener}s
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
     * The default value is 0
     * @return the time in ms after which idle destinations are removed
     * @see #setDestinationIdleTimeout(long)
     */
    @ManagedAttribute("The time in ms after which idle destinations are removed, disabled when zero or negative")
    public long getDestinationIdleTimeout()
    {
        return destinationIdleTimeout;
    }

    /**
     * <p>
     * Whether destinations that have no connections (nor active nor idle) and no exchanges
     * should be removed after the specified timeout.
     * </p>
     * <p>
     * If the specified {@code destinationIdleTimeout} is 0 or negative, then the destinations
     * are not removed.
     * </p>
     * <p>
     * Avoids accumulating destinations when applications (e.g. a spider bot or web crawler)
     * hit a lot of different destinations that won't be visited again.
     * </p>
     *
     * @param destinationIdleTimeout the time in ms after which idle destinations are removed
     */
    public void setDestinationIdleTimeout(long destinationIdleTimeout)
    {
        if (isStarted())
            throw new IllegalStateException();
        this.destinationIdleTimeout = destinationIdleTimeout;
    }

    /**
     * @return whether {@code connect()} operations are performed in blocking mode
     */
    @ManagedAttribute("Whether the connect() operation is blocking")
    public boolean isConnectBlocking()
    {
        return connector.isConnectBlocking();
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
        connector.setConnectBlocking(connectBlocking);
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
     * @return whether to use direct ByteBuffers for reading
     */
    @ManagedAttribute("Whether to use direct ByteBuffers for reading")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    /**
     * @param useInputDirectByteBuffers whether to use direct ByteBuffers for reading
     */
    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    /**
     * @return whether to use direct ByteBuffers for writing
     */
    @ManagedAttribute("Whether to use direct ByteBuffers for writing")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    /**
     * @param useOutputDirectByteBuffers whether to use direct ByteBuffers for writing
     */
    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    /**
     * @return the forward proxy configuration
     */
    public ProxyConfiguration getProxyConfiguration()
    {
        return proxyConfig;
    }

    public static int normalizePort(String scheme, int port)
    {
        if (port > 0)
            return port;
        return HttpScheme.getDefaultPort(scheme);
    }

    public boolean isDefaultPort(String scheme, int port)
    {
        return HttpScheme.getDefaultPort(scheme) == port;
    }

    public static boolean isSchemeSecure(String scheme)
    {
        return HttpScheme.HTTPS.is(scheme) || HttpScheme.WSS.is(scheme);
    }

    public ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory.Client sslContextFactory, ClientConnectionFactory connectionFactory)
    {
        if (sslContextFactory == null)
            sslContextFactory = getSslContextFactory();
        return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory);
    }
}
