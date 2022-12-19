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

package org.eclipse.jetty.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.EarlyHintsProtocolHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProcessingProtocolHandler;
import org.eclipse.jetty.client.ProtocolHandlers;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Handler} that can be used to implement a {@link Forward forward
 * proxy ("proxy")} or a {@link Reverse reverse proxy ("gateway")} as defined by
 * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-2.3">RFC 7230</a>.</p>
 * <p>This class uses {@link HttpClient} to send requests from the proxy to the server.</p>
 * <p>The {@code HttpClient} instance is either
 * {@link #setHttpClient(HttpClient) set explicitly}, or created implicitly.
 * To customize the implicit {@code HttpClient} instance, applications can
 * override {@link #newHttpClient()} and {@link #configureHttpClient(HttpClient)}.</p>
 *
 * @see Forward
 * @see Reverse
 */
public abstract class ProxyHandler extends Handler.Abstract
{
    private static final Logger LOG = LoggerFactory.getLogger(ProxyHandler.class);
    private static final String CLIENT_TO_PROXY_REQUEST_ATTRIBUTE = ProxyHandler.class.getName() + ".clientToProxyRequest";
    private static final String PROXY_TO_CLIENT_RESPONSE_ATTRIBUTE = ProxyHandler.class.getName() + ".proxyToClientResponse";
    private static final String PROXY_TO_SERVER_CONTINUE_ATTRIBUTE = ProxyHandler.class.getName() + ".proxyToServerContinue";
    private static final EnumSet<HttpHeader> HOP_HEADERS = EnumSet.of(
        HttpHeader.CONNECTION,
        HttpHeader.KEEP_ALIVE,
        HttpHeader.PROXY_AUTHORIZATION,
        HttpHeader.PROXY_AUTHENTICATE,
        HttpHeader.PROXY_CONNECTION,
        HttpHeader.TRANSFER_ENCODING,
        HttpHeader.TE,
        HttpHeader.TRAILER,
        HttpHeader.UPGRADE
    );

    private HttpClient httpClient;
    private String proxyToServerHost;
    private String viaHost;

    public HttpClient getHttpClient()
    {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    /**
     * @return the proxy-to-server {@code Host} header value
     */
    public String getProxyToServerHost()
    {
        return proxyToServerHost;
    }

    /**
     * <p>Sets the value to use for the {@code Host} header in proxy-to-server requests.</p>
     * <p>If {@code null}, the client-to-proxy value is used.</p>
     *
     * @param host the proxy-to-server {@code Host} header value
     */
    public void setProxyToServerHost(String host)
    {
        this.proxyToServerHost = host;
    }

    /**
     * @return the value to use for the {@code Via} header
     */
    public String getViaHost()
    {
        return viaHost;
    }

    /**
     * <p>Sets the value to use for the {@code Via} header in proxy-to-server requests.</p>
     * <p>If {@code null}, the local host name is used.</p>
     *
     * @param viaHost the value to use for the {@code Via} header
     */
    public void setViaHost(String viaHost)
    {
        this.viaHost = viaHost;
    }

    private static String viaHost()
    {
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException x)
        {
            return "localhost";
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (httpClient == null)
            setHttpClient(createHttpClient());
        addBean(httpClient, true);

        if (viaHost == null)
            setViaHost(viaHost());

        super.doStart();
    }

    private HttpClient createHttpClient()
    {
        HttpClient httpClient = newHttpClient();
        configureHttpClient(httpClient);
        LifeCycle.start(httpClient);
        httpClient.getContentDecoderFactories().clear();
        ProtocolHandlers protocolHandlers = httpClient.getProtocolHandlers();
        protocolHandlers.clear();
        protocolHandlers.put(new ProxyContinueProtocolHandler());
        protocolHandlers.put(new ProxyProcessingProtocolHandler());
        protocolHandlers.put(new ProxyEarlyHintsProtocolHandler());
        return httpClient;
    }

    /**
     * <p>Creates a new {@link HttpClient} instance, by default with a thread
     * pool named {@code proxy-client} and with the
     * {@link HttpClientTransportDynamic dynamic transport} configured only
     * with HTTP/1.1.</p>
     *
     * @return a new {@code HttpClient} instance
     */
    protected HttpClient newHttpClient()
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool proxyClientThreads = new QueuedThreadPool();
        proxyClientThreads.setName("proxy-client");
        clientConnector.setExecutor(proxyClientThreads);
        return new HttpClient(new HttpClientTransportDynamic(clientConnector));
    }

    /**
     * <p>Configures the {@link HttpClient} instance before it is started.</p>
     *
     * @param httpClient the {@code HttpClient} instance to configure
     */
    protected void configureHttpClient(HttpClient httpClient)
    {
        httpClient.setFollowRedirects(false);
        httpClient.setCookieStore(new HttpCookieStore.Empty());
    }

    protected static String requestId(Request clientToProxyRequest)
    {
        return String.valueOf(System.identityHashCode(clientToProxyRequest));
    }

    @Override
    public boolean process(Request clientToProxyRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("""
                {} C2P received request
                {}
                {}""",
                requestId(clientToProxyRequest),
                clientToProxyRequest,
                clientToProxyRequest.getHeaders());

        HttpURI rewritten = rewriteHttpURI(clientToProxyRequest);
        if (LOG.isDebugEnabled())
            LOG.debug("{} URI rewrite {} => {}", requestId(clientToProxyRequest), clientToProxyRequest.getHttpURI(), rewritten);

        var proxyToServerRequest = newProxyToServerRequest(clientToProxyRequest, rewritten);
        proxyToServerRequest.attribute(CLIENT_TO_PROXY_REQUEST_ATTRIBUTE, clientToProxyRequest)
            .attribute(PROXY_TO_CLIENT_RESPONSE_ATTRIBUTE, proxyToClientResponse);

        copyRequestHeaders(clientToProxyRequest, proxyToServerRequest);

        addProxyHeaders(clientToProxyRequest, proxyToServerRequest);

        if (hasContent(clientToProxyRequest))
        {
            if (expects100Continue(clientToProxyRequest))
            {
                // Delay reading the content until the server sends 100 Continue.
                AsyncRequestContent delayedProxyToServerRequestContent = new AsyncRequestContent();
                proxyToServerRequest.body(delayedProxyToServerRequestContent);
                Runnable action = () ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} P2S continuing request", requestId(clientToProxyRequest));
                    var proxyToServerRequestContent = newProxyToServerRequestContent(clientToProxyRequest, proxyToClientResponse, proxyToServerRequest);
                    Content.copy(proxyToServerRequestContent, delayedProxyToServerRequestContent,
                        Callback.from(delayedProxyToServerRequestContent::close, delayedProxyToServerRequestContent::fail));
                };
                proxyToServerRequest.attribute(PROXY_TO_SERVER_CONTINUE_ATTRIBUTE, action);
            }
            else
            {
                var proxyToServerRequestContent = newProxyToServerRequestContent(clientToProxyRequest, proxyToClientResponse, proxyToServerRequest);
                proxyToServerRequest.body(proxyToServerRequestContent);
            }
        }

        sendProxyToServerRequest(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback);
        return true;
    }

    /**
     * <p>Rewrites the client-to-proxy request URI to the proxy-to-server request URI.</p>
     *
     * @param clientToProxyRequest the client-to-proxy request
     * @return an {@code HttpURI} for the proxy-to-server request
     */
    protected abstract HttpURI rewriteHttpURI(Request clientToProxyRequest);

    protected org.eclipse.jetty.client.api.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
    {
        return getHttpClient().newRequest(newHttpURI.toURI())
            .method(clientToProxyRequest.getMethod());
    }

    protected void copyRequestHeaders(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest)
    {
        Set<String> headersToRemove = findConnectionHeaders(clientToProxyRequest);

        for (HttpField clientToProxyRequestField : clientToProxyRequest.getHeaders())
        {
            HttpHeader clientToProxyRequestHeader = clientToProxyRequestField.getHeader();

            if (HttpHeader.HOST == clientToProxyRequestHeader)
            {
                String host = getProxyToServerHost();
                if (host != null)
                {
                    proxyToServerRequest.headers(headers -> headers.put(HttpHeader.HOST, host));
                    continue;
                }
            }

            if (HOP_HEADERS.contains(clientToProxyRequestHeader))
                continue;
            if (headersToRemove != null && headersToRemove.contains(clientToProxyRequestField.getLowerCaseName()))
                continue;

            proxyToServerRequest.headers(headers -> headers.add(clientToProxyRequestField));
        }
    }

    private Set<String> findConnectionHeaders(Request clientToProxyRequest)
    {
        // Any header listed by the Connection header must be removed:
        // http://tools.ietf.org/html/rfc7230#section-6.1.
        Set<String> hopHeaders = null;
        List<String> connectionHeaders = clientToProxyRequest.getHeaders().getValuesList(HttpHeader.CONNECTION);
        for (String value : connectionHeaders)
        {
            String[] values = value.split(",");
            for (String name : values)
            {
                name = name.trim().toLowerCase(Locale.ENGLISH);
                if (hopHeaders == null)
                    hopHeaders = new HashSet<>();
                hopHeaders.add(name);
            }
        }
        return hopHeaders;
    }

    protected void addProxyHeaders(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest)
    {
        addViaHeader(clientToProxyRequest, proxyToServerRequest);
        addForwardedHeader(clientToProxyRequest, proxyToServerRequest);
    }

    protected void addViaHeader(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest)
    {
        String protocol = clientToProxyRequest.getConnectionMetaData().getProtocol();
        String[] parts = protocol.split("/", 2);
        // Retain only the version if the protocol is HTTP.
        String protocolPart = parts.length == 2 && "HTTP".equalsIgnoreCase(parts[0]) ? parts[1] : protocol;
        String viaHeaderValue = protocolPart + " " + getViaHost();
        proxyToServerRequest.headers(headers -> headers.computeField(HttpHeader.VIA, (header, viaFields) ->
        {
            if (viaFields == null || viaFields.isEmpty())
                return new HttpField(header, viaHeaderValue);
            String separator = ", ";
            String newValue = viaFields.stream()
                .flatMap(field -> Stream.of(field.getValues()))
                .filter(value -> !StringUtil.isBlank(value))
                .collect(Collectors.joining(separator));
            if (newValue.length() > 0)
                newValue += separator;
            newValue += viaHeaderValue;
            return new HttpField(HttpHeader.VIA, newValue);
        }));
    }

    protected void addForwardedHeader(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest)
    {
        String byAttr = Request.getLocalAddr(clientToProxyRequest);
        String forAttr = Request.getRemoteAddr(clientToProxyRequest);
        String hostAttr = clientToProxyRequest.getHeaders().get(HttpHeader.HOST);
        String scheme = clientToProxyRequest.getHttpURI().getScheme();
        // Even if the request came through a secure channel, look at the original scheme if present.
        // For example, a client with a forward proxy may want to communicate in clear-text with the
        // server (so the scheme is http), but securely with the forward proxy (so isSecure() is true).
        String protoAttr = scheme == null ? (clientToProxyRequest.isSecure() ? "https" : "http") : scheme;
        String forwardedValue = "by=%s;for=%s;host=%s;proto=%s".formatted(
            QuotedStringTokenizer.quote(byAttr),
            QuotedStringTokenizer.quote(forAttr),
            QuotedStringTokenizer.quote(hostAttr),
            protoAttr
        );

        proxyToServerRequest.headers(headers -> headers.computeField(HttpHeader.FORWARDED, (header, fields) ->
        {
            String newValue;
            if (fields == null || fields.isEmpty())
            {
                newValue = forwardedValue;
            }
            else
            {
                String separator = ", ";
                newValue = fields.stream()
                    .flatMap(field -> field.getValueList().stream())
                    .collect(Collectors.joining(separator));
                newValue += separator + forwardedValue;
            }
            return new HttpField(HttpHeader.FORWARDED, newValue);
        }));
    }

    private boolean hasContent(Request clientToProxyRequest)
    {
        if (clientToProxyRequest.getLength() > 0)
            return true;
        HttpFields headers = clientToProxyRequest.getHeaders();
        return headers.get(HttpHeader.CONTENT_TYPE) != null ||
               headers.get(HttpHeader.TRANSFER_ENCODING) != null;
    }

    private boolean expects100Continue(Request clientToProxyRequest)
    {
        return HttpHeaderValue.CONTINUE.is(clientToProxyRequest.getHeaders().get(HttpHeader.EXPECT));
    }

    protected org.eclipse.jetty.client.api.Request.Content newProxyToServerRequestContent(Request clientToProxyRequest, Response proxyToClientResponse, org.eclipse.jetty.client.api.Request proxyToServerRequest)
    {
        return new ProxyRequestContent(clientToProxyRequest);
    }

    protected void sendProxyToServerRequest(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("""
                    {} P2S sending request
                    {}
                    {}""",
                requestId(clientToProxyRequest),
                proxyToServerRequest,
                proxyToServerRequest.getHeaders());
        }
        proxyToServerRequest.send(newServerToProxyResponseListener(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback));
    }

    protected org.eclipse.jetty.client.api.Response.CompleteListener newServerToProxyResponseListener(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
    {
        return new ProxyResponseListener(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback);
    }

    protected HttpField filterServerToProxyResponseField(HttpField serverToProxyResponseField)
    {
        return serverToProxyResponseField;
    }

    protected void onServerToProxyResponseFailure(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, org.eclipse.jetty.client.api.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback, Throwable failure)
    {
        int status = HttpStatus.BAD_GATEWAY_502;
        if (failure instanceof TimeoutException)
            status = HttpStatus.GATEWAY_TIMEOUT_504;
        Callback callback = new ProxyToClientResponseFailureCallback(clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback);
        Response.writeError(clientToProxyRequest, proxyToClientResponse, callback, status);
    }

    protected void onServerToProxyResponse100Continue(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} P2C 100 continue response", requestId(clientToProxyRequest));
        Runnable action = (Runnable)proxyToServerRequest.getAttributes().get(PROXY_TO_SERVER_CONTINUE_ATTRIBUTE);
        action.run();
    }

    protected void onServerToProxyResponse102Processing(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, HttpFields serverToProxyResponseHeaders, Response proxyToClientResponse)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} P2C 102 interim response {}", requestId(clientToProxyRequest), serverToProxyResponseHeaders);
        proxyToClientResponse.writeInterim(HttpStatus.PROCESSING_102, serverToProxyResponseHeaders);
    }

    protected void onServerToProxyResponse103EarlyHints(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, HttpFields serverToProxyResponseHeaders, Response proxyToClientResponse)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} P2C 103 interim response {}", requestId(clientToProxyRequest), serverToProxyResponseHeaders);
        proxyToClientResponse.writeInterim(HttpStatus.EARLY_HINT_103, serverToProxyResponseHeaders);
    }

    protected void onProxyToClientResponseComplete(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, org.eclipse.jetty.client.api.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback)
    {
        proxyToClientCallback.succeeded();
    }

    protected void onProxyToClientResponseFailure(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, org.eclipse.jetty.client.api.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback, Throwable failure)
    {
        // There is no point trying to write an error,
        // we already know we cannot write to the client.
        proxyToClientCallback.failed(failure);
    }

    /**
     * <p>A {@code ProxyHandler} that can be used to implement a forward proxy server.</p>
     * <p>Forward proxies are configured in client applications that use
     * {@link HttpClient} in this way:</p>
     * <pre>{@code
     * httpClient.getProxyConfiguration().addProxy(new HttpProxy(proxyHost, proxyPort));
     * }</pre>
     *
     * @see org.eclipse.jetty.client.ProxyConfiguration
     * @see org.eclipse.jetty.client.HttpProxy
     * @see Reverse
     */
    public static class Forward extends ProxyHandler
    {
        /**
         * {@inheritDoc}
         * <p>Applications that use this class should return the client-to-proxy
         * request URI, since clients will send the absolute URI of the server.</p>
         *
         * @param clientToProxyRequest the client-to-proxy request
         * @return the client-to-proxy request URI
         */
        @Override
        protected HttpURI rewriteHttpURI(Request clientToProxyRequest)
        {
            return clientToProxyRequest.getHttpURI();
        }
    }

    /**
     * <p>A {@code ProxyHandler} that can be used to implement a reverse proxy.</p>
     * <p>A reverse proxy must rewrite the client-to-proxy request URI into the
     * proxy-to-server request URI.
     * This can be done by providing a rewrite function to the constructor,
     * and/or override {@link #rewriteHttpURI(Request)}.</p>
     *
     * @see Forward
     */
    public static class Reverse extends ProxyHandler
    {
        private final Function<Request, HttpURI> httpURIRewriter;

        /**
         * <p>Convenience constructor that provides a rewrite function
         * using {@link String#replaceAll(String, String)}.</p>
         * <p>As a simple example, given the URI pattern of:</p>
         * <p>{@code (https?)://([a-z]+):([0-9]+)/([^/]+)/(.*)}</p>
         * <p>and given a replacement string of:</p>
         * <p>{@code $1://$2:9000/proxy/$5}</p>
         * <p>an incoming {@code HttpURI} of:</p>
         * <p>{@code http://host:8080/ctx/path}</p>
         * <p>will be rewritten as:</p>
         * <p>{@code http://host:9000/proxy/path}</p>
         *
         * @param uriPattern the regex pattern to use to match the incoming URI
         * @param uriReplacement the replacement string to use to rewrite the incoming URI
         */
        public Reverse(String uriPattern, String uriReplacement)
        {
            this(request ->
            {
                String uri = request.getHttpURI().toString();
                return HttpURI.build(uri.replaceAll(uriPattern, uriReplacement));
            });
        }

        /**
         * <p>Creates a new instance with the given {@code HttpURI} rewrite function.</p>
         * <p>The rewrite functions rewrites the client-to-proxy request URI into the
         * proxy-to-server request URI.</p>
         *
         * @param httpURIRewriter a function that returns the URI of the server
         */
        public Reverse(Function<Request, HttpURI> httpURIRewriter)
        {
            this.httpURIRewriter = Objects.requireNonNull(httpURIRewriter);
        }

        public Function<Request, HttpURI> getHttpURIRewriter()
        {
            return httpURIRewriter;
        }

        /**
         * {@inheritDoc}
         * <p>Applications that use this class typically provide a rewrite
         * function to the constructor.</p>
         * <p>The rewrite function rewrites the client-to-proxy request URI,
         * for example {@code http://example.com/app/path}, to the proxy-to-server
         * request URI, for example {@code http://backend1:8080/legacy/path}.</p>
         *
         * @param clientToProxyRequest the client-to-proxy request
         * @return the proxy-to-server request URI.
         */
        @Override
        protected HttpURI rewriteHttpURI(Request clientToProxyRequest)
        {
            return getHttpURIRewriter().apply(clientToProxyRequest);
        }
    }

    protected static class ProxyRequestContent implements org.eclipse.jetty.client.api.Request.Content
    {
        private final Request clientToProxyRequest;

        public ProxyRequestContent(Request clientToProxyRequest)
        {
            this.clientToProxyRequest = clientToProxyRequest;
        }

        @Override
        public long getLength()
        {
            return clientToProxyRequest.getLength();
        }

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = clientToProxyRequest.read();
            if (LOG.isDebugEnabled())
                LOG.debug("{} C2P read content {}", requestId(clientToProxyRequest), chunk);
            return chunk;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            clientToProxyRequest.demand(demandCallback);
        }

        @Override
        public void fail(Throwable failure)
        {
            clientToProxyRequest.fail(failure);
        }

        @Override
        public String getContentType()
        {
            return clientToProxyRequest.getHeaders().get(HttpHeader.CONTENT_TYPE);
        }

        @Override
        public boolean rewind()
        {
            return clientToProxyRequest.rewind();
        }
    }

    protected class ProxyResponseListener extends Callback.Completable implements org.eclipse.jetty.client.api.Response.Listener
    {
        private final Request clientToProxyRequest;
        private final org.eclipse.jetty.client.api.Request proxyToServerRequest;
        private final Response proxyToClientResponse;
        private final Callback proxyToClientCallback;

        public ProxyResponseListener(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
        {
            this.clientToProxyRequest = clientToProxyRequest;
            this.proxyToServerRequest = proxyToServerRequest;
            this.proxyToClientResponse = proxyToClientResponse;
            this.proxyToClientCallback = proxyToClientCallback;
        }

        @Override
        public void onBegin(org.eclipse.jetty.client.api.Response serverToProxyResponse)
        {
            proxyToClientResponse.setStatus(serverToProxyResponse.getStatus());
        }

        @Override
        public void onHeaders(org.eclipse.jetty.client.api.Response serverToProxyResponse)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("""
                        {} S2P received response
                        {}
                        {}""",
                    requestId(clientToProxyRequest),
                    serverToProxyResponse,
                    serverToProxyResponse.getHeaders());
            }
            for (HttpField serverToProxyResponseField : serverToProxyResponse.getHeaders())
            {
                if (HOP_HEADERS.contains(serverToProxyResponseField.getHeader()))
                    continue;
                HttpField newField = filterServerToProxyResponseField(serverToProxyResponseField);
                if (newField == null)
                    continue;
                proxyToClientResponse.getHeaders().add(newField);
            }
            if (LOG.isDebugEnabled())
            {
                LOG.debug("""
                        {} P2C sending response
                        {}
                        {}""",
                    requestId(clientToProxyRequest),
                    proxyToClientResponse,
                    proxyToClientResponse.getHeaders());
            }
        }

        @Override
        public void onContent(org.eclipse.jetty.client.api.Response serverToProxyResponse, ByteBuffer serverToProxyContent, Callback serverToProxyContentCallback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} S2P received content {}", requestId(clientToProxyRequest), BufferUtil.toDetailString(serverToProxyContent));
            Callback callback = new Callback()
            {
                @Override
                public void succeeded()
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} P2C succeeded to write content {}", requestId(clientToProxyRequest), BufferUtil.toDetailString(serverToProxyContent));
                    serverToProxyContentCallback.succeeded();
                }

                @Override
                public void failed(Throwable failure)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} P2C failed to write content {}", requestId(clientToProxyRequest), BufferUtil.toDetailString(serverToProxyContent), failure);
                    serverToProxyContentCallback.failed(failure);
                    // Cannot write towards the client, abort towards the server.
                    serverToProxyResponse.abort(failure);
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return InvocationType.NON_BLOCKING;
                }
            };
            proxyToClientResponse.write(false, serverToProxyContent, callback);
        }

        @Override
        public void onSuccess(org.eclipse.jetty.client.api.Response serverToProxyResponse)
        {
            proxyToClientResponse.write(true, BufferUtil.EMPTY_BUFFER, this);
        }

        @Override
        public void onComplete(Result result)
        {
            if (result.isSucceeded())
            {
                // Wait for the last write to complete.
                whenComplete((r, failure) ->
                {
                    if (failure == null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} P2C response complete {}", requestId(clientToProxyRequest), proxyToClientResponse);
                        onProxyToClientResponseComplete(clientToProxyRequest, proxyToServerRequest, result.getResponse(), proxyToClientResponse, proxyToClientCallback);
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} P2C response failure {}", requestId(clientToProxyRequest), proxyToClientResponse, failure);
                        onProxyToClientResponseFailure(clientToProxyRequest, proxyToServerRequest, result.getResponse(), proxyToClientResponse, proxyToClientCallback, failure);
                    }
                });
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} S2P failure {}", requestId(clientToProxyRequest), result.getResponse(), result.getFailure());
                onServerToProxyResponseFailure(clientToProxyRequest, proxyToServerRequest, result.getResponse(), proxyToClientResponse, proxyToClientCallback, result.getFailure());
            }
        }
    }

    private class ProxyToClientResponseFailureCallback implements Callback
    {
        private final Request clientToProxyRequest;
        private final org.eclipse.jetty.client.api.Request proxyToServerRequest;
        private final org.eclipse.jetty.client.api.Response serverToProxyResponse;
        private final Response proxyToClientResponse;
        private final Callback proxyToClientCallback;

        private ProxyToClientResponseFailureCallback(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, org.eclipse.jetty.client.api.Response serverToProxyResponse, Response proxyToClientResponse, Callback proxyToClientCallback)
        {
            this.clientToProxyRequest = clientToProxyRequest;
            this.proxyToServerRequest = proxyToServerRequest;
            this.serverToProxyResponse = serverToProxyResponse;
            this.proxyToClientResponse = proxyToClientResponse;
            this.proxyToClientCallback = proxyToClientCallback;
        }

        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} P2C response complete {}", requestId(clientToProxyRequest), proxyToClientResponse);
            onProxyToClientResponseComplete(clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback);
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} P2C response failure {}", requestId(clientToProxyRequest), proxyToClientResponse, x);
            onProxyToClientResponseFailure(clientToProxyRequest, proxyToServerRequest, serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }

    private class ProxyContinueProtocolHandler extends ContinueProtocolHandler
    {
        @Override
        protected void onContinue(org.eclipse.jetty.client.api.Request proxyToServerRequest)
        {
            super.onContinue(proxyToServerRequest);
            var clientToProxyRequest = (Request)proxyToServerRequest.getAttributes().get(CLIENT_TO_PROXY_REQUEST_ATTRIBUTE);
            if (LOG.isDebugEnabled())
                LOG.debug("{} S2P received 100 Continue", requestId(clientToProxyRequest));
            onServerToProxyResponse100Continue(clientToProxyRequest, proxyToServerRequest);
        }
    }

    private class ProxyProcessingProtocolHandler extends ProcessingProtocolHandler
    {
        @Override
        protected void onProcessing(org.eclipse.jetty.client.api.Request proxyToServerRequest, HttpFields serverToProxyResponseHeaders)
        {
            super.onProcessing(proxyToServerRequest, serverToProxyResponseHeaders);
            var clientToProxyRequest = (Request)proxyToServerRequest.getAttributes().get(CLIENT_TO_PROXY_REQUEST_ATTRIBUTE);
            if (LOG.isDebugEnabled())
                LOG.debug("{} S2P received 102 Processing", requestId(clientToProxyRequest));
            var proxyToClientResponse = (Response)proxyToServerRequest.getAttributes().get(PROXY_TO_CLIENT_RESPONSE_ATTRIBUTE);
            onServerToProxyResponse102Processing(clientToProxyRequest, proxyToServerRequest, serverToProxyResponseHeaders, proxyToClientResponse);
        }
    }

    private class ProxyEarlyHintsProtocolHandler extends EarlyHintsProtocolHandler
    {
        @Override
        protected void onEarlyHints(org.eclipse.jetty.client.api.Request proxyToServerRequest, HttpFields serverToProxyResponseHeaders)
        {
            super.onEarlyHints(proxyToServerRequest, serverToProxyResponseHeaders);
            var clientToProxyRequest = (Request)proxyToServerRequest.getAttributes().get(CLIENT_TO_PROXY_REQUEST_ATTRIBUTE);
            if (LOG.isDebugEnabled())
                LOG.debug("{} S2P received 103 Early Hints", requestId(clientToProxyRequest));
            var proxyToClientResponse = (Response)proxyToServerRequest.getAttributes().get(PROXY_TO_CLIENT_RESPONSE_ATTRIBUTE);
            onServerToProxyResponse103EarlyHints(clientToProxyRequest, proxyToServerRequest, serverToProxyResponseHeaders, proxyToClientResponse);
        }
    }
}
