//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.CompleteListener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionUpgrader;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.WebSocketConstants;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.client.io.WebSocketClientConnection;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;

public class WebSocketUpgradeRequest extends HttpRequest implements CompleteListener, HttpConnectionUpgrader
{
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeRequest.class);

    private class ClientUpgradeRequestFacade implements UpgradeRequest
    {
        private List<ExtensionConfig> extensions;
        private List<String> subProtocols;
        private Object session;

        public ClientUpgradeRequestFacade()
        {
            this.extensions = new ArrayList<>();
            this.subProtocols = new ArrayList<>();
        }

        public void init(ClientUpgradeRequest request)
        {
            this.extensions = new ArrayList<>(request.getExtensions());
            this.subProtocols = new ArrayList<>(request.getSubProtocols());

            request.getHeaders().forEach((name, values) ->
                values.forEach((value) -> header(name, value))
            );

            for (HttpCookie cookie : request.getCookies())
            {
                cookie(cookie);
            }
        }

        @Override
        public List<ExtensionConfig> getExtensions()
        {
            return extensions;
        }

        @Override
        public List<String> getSubProtocols()
        {
            return subProtocols;
        }

        @Override
        public void addExtensions(ExtensionConfig... configs)
        {
            for (ExtensionConfig config : configs)
            {
                this.extensions.add(config);
            }
            updateExtensionHeader();
        }

        @Override
        public void addExtensions(String... configs)
        {
            this.extensions.addAll(ExtensionConfig.parseList(configs));
            updateExtensionHeader();
        }

        @Override
        public void clearHeaders()
        {
            throw new UnsupportedOperationException("Clearing all headers breaks WebSocket upgrade");
        }

        @Override
        public String getHeader(String name)
        {
            return getHttpFields().get(name);
        }

        @Override
        public int getHeaderInt(String name)
        {
            String value = getHttpFields().get(name);
            if (value == null)
            {
                return -1;
            }
            return Integer.parseInt(value);
        }

        @Override
        public List<String> getHeaders(String name)
        {
            return getHttpFields().getValuesList(name);
        }

        @Override
        public String getHttpVersion()
        {
            return getVersion().asString();
        }

        @Override
        public String getOrigin()
        {
            return getHttpFields().get(HttpHeader.ORIGIN);
        }

        @Override
        public Map<String, List<String>> getParameterMap()
        {
            Map<String, List<String>> paramMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

            String query = getQueryString();
            MultiMap<String> multimap = new MultiMap<>();
            UrlEncoded.decodeTo(query, multimap, StandardCharsets.UTF_8);

            paramMap.putAll(multimap);

            return paramMap;
        }

        @Override
        public String getProtocolVersion()
        {
            String ver = getHttpFields().get(HttpHeader.SEC_WEBSOCKET_VERSION);
            if (ver == null)
            {
                return Integer.toString(WebSocketConstants.SPEC_VERSION);
            }
            return ver;
        }

        @Override
        public String getQueryString()
        {
            return getURI().getQuery();
        }

        @Override
        public URI getRequestURI()
        {
            return getURI();
        }

        @Override
        public Object getSession()
        {
            return this.session;
        }

        @Override
        public Principal getUserPrincipal()
        {
            // HttpClient doesn't use Principal concepts
            return null;
        }

        @Override
        public boolean hasSubProtocol(String test)
        {
            return getSubProtocols().contains(test);
        }

        @Override
        public boolean isOrigin(String test)
        {
            return test.equalsIgnoreCase(getOrigin());
        }

        @Override
        public boolean isSecure()
        {
            // TODO: need to obtain information from actual request to know of SSL was used?
            return "wss".equalsIgnoreCase(getURI().getScheme());
        }

        @Override
        public void setCookies(List<HttpCookie> cookies)
        {
            for (HttpCookie cookie : cookies)
            {
                cookie(cookie);
            }
        }

        @Override
        public void setExtensions(List<ExtensionConfig> configs)
        {
            this.extensions = configs;
            updateExtensionHeader();
        }

        private void updateExtensionHeader()
        {
            HttpFields headers = getHttpFields();
            headers.remove(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
            for (ExtensionConfig config : extensions)
            {
                headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, config.getParameterizedName());
            }
        }

        @Override
        public void setHeader(String name, List<String> values)
        {
            getHttpFields().put(name, values);
        }

        @Override
        public void setHeader(String name, String value)
        {
            getHttpFields().put(name, value);
        }

        @Override
        public void setHeaders(Map<String, List<String>> headers)
        {
            for (Map.Entry<String, List<String>> entry : headers.entrySet())
            {
                getHttpFields().put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void setHttpVersion(String httpVersion)
        {
            version(HttpVersion.fromString(httpVersion));
        }

        @Override
        public void setMethod(String method)
        {
            method(method);
        }

        @Override
        public void setRequestURI(URI uri)
        {
            throw new UnsupportedOperationException("Cannot reset/change RequestURI");
        }

        @Override
        public void setSession(Object session)
        {
            this.session = session;
        }

        @Override
        public void setSubProtocols(List<String> protocols)
        {
            this.subProtocols = protocols;
        }

        @Override
        public void setSubProtocols(String... protocols)
        {
            this.subProtocols.clear();
            this.subProtocols.addAll(Arrays.asList(protocols));
        }

        @Override
        public List<HttpCookie> getCookies()
        {
            return WebSocketUpgradeRequest.this.getCookies();
        }

        @Override
        public Map<String, List<String>> getHeaders()
        {
            Map<String, List<String>> headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            HttpFields fields = getHttpFields();
            for (String name : fields.getFieldNamesCollection())
            {
                headersMap.put(name, fields.getValuesList(name));
            }
            return headersMap;
        }

        @Override
        public String getHost()
        {
            return WebSocketUpgradeRequest.this.getHost();
        }

        @Override
        public String getMethod()
        {
            return WebSocketUpgradeRequest.this.getMethod();
        }
    }

    private final WebSocketClient wsClient;
    private final EventDriver localEndpoint;
    private final CompletableFuture<Session> fut;
    /**
     * WebSocket API UpgradeRequest Facade to HttpClient HttpRequest
     */
    private final ClientUpgradeRequestFacade apiRequestFacade;
    private UpgradeListener upgradeListener;

    /**
     * Exists for internal use of HttpClient by WebSocketClient.
     * <p>
     * Maintained for Backward compatibility and also for JSR356 WebSocket ClientContainer use.
     *
     * @param wsClient the WebSocketClient that this request uses
     * @param httpClient the HttpClient that this request uses
     * @param request the ClientUpgradeRequest (backward compat) to base this request from
     */
    protected WebSocketUpgradeRequest(WebSocketClient wsClient, HttpClient httpClient, ClientUpgradeRequest request)
    {
        this(wsClient, httpClient, request.getRequestURI(), request.getLocalEndpoint());
        apiRequestFacade.init(request);
    }

    /**
     * Initiating a WebSocket Upgrade using HTTP/1.1
     *
     * @param wsClient the WebSocketClient that this request uses
     * @param httpClient the HttpClient that this request uses
     * @param localEndpoint the local endpoint (following Jetty WebSocket Client API rules) to use for incoming
     * WebSocket events
     * @param wsURI the WebSocket URI to connect to
     */
    public WebSocketUpgradeRequest(WebSocketClient wsClient, HttpClient httpClient, URI wsURI, Object localEndpoint)
    {
        super(httpClient, new HttpConversation(), wsURI);

        apiRequestFacade = new ClientUpgradeRequestFacade();

        if (!wsURI.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be an absolute URI: " + wsURI);
        }

        String scheme = wsURI.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss")))
        {
            throw new IllegalArgumentException("WebSocket URI must use 'ws' or 'wss' scheme: " + wsURI);
        }

        this.wsClient = wsClient;
        try
        {
            if (!this.wsClient.isRunning())
            {
                this.wsClient.start();
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Unable to start WebSocketClient", e);
        }
        this.localEndpoint = this.wsClient.getEventDriverFactory().wrap(localEndpoint);

        this.fut = new CompletableFuture<>();
        this.fut.whenComplete((session, throwable) ->
        {
            if (throwable != null)
                abort(throwable);
        });

        getConversation().setAttribute(HttpConnectionUpgrader.class.getName(), this);
    }

    private String genRandomKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private ExtensionFactory getExtensionFactory()
    {
        return this.wsClient.getExtensionFactory();
    }

    private SessionFactory getSessionFactory()
    {
        return this.wsClient.getSessionFactory();
    }

    private void initWebSocketHeaders()
    {
        method(HttpMethod.GET);
        version(HttpVersion.HTTP_1_1);

        // The Upgrade Headers
        header(HttpHeader.UPGRADE, "websocket");
        header(HttpHeader.CONNECTION, "Upgrade");

        // The WebSocket Headers
        header(HttpHeader.SEC_WEBSOCKET_KEY, genRandomKey());
        header(HttpHeader.SEC_WEBSOCKET_VERSION, "13");

        // (Per the hybi list): Add no-cache headers to avoid compatibility issue.
        // There are some proxies that rewrite "Connection: upgrade"
        // to "Connection: close" in the response if a request doesn't contain
        // these headers.
        header(HttpHeader.PRAGMA, "no-cache");
        header(HttpHeader.CACHE_CONTROL, "no-cache");

        // handle "Sec-WebSocket-Extensions"
        if (!apiRequestFacade.getExtensions().isEmpty())
        {
            for (ExtensionConfig ext : apiRequestFacade.getExtensions())
            {
                header(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, ext.getParameterizedName());
            }
        }

        // handle "Sec-WebSocket-Protocol"
        if (!apiRequestFacade.getSubProtocols().isEmpty())
        {
            for (String protocol : apiRequestFacade.getSubProtocols())
            {
                header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
            }
        }

        if (upgradeListener != null)
        {
            upgradeListener.onHandshakeRequest(apiRequestFacade);
        }
    }

    @Override
    public void onComplete(Result result)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onComplete() - {}", result);
        }

        URI requestURI = result.getRequest().getURI();
        Response response = result.getResponse();
        int responseStatusCode = response.getStatus();
        String responseLine = responseStatusCode + " " + response.getReason();

        if (result.isFailed())
        {
            if (LOG.isDebugEnabled())
            {
                if (result.getFailure() != null)
                    LOG.debug("General Failure", result.getFailure());
                if (result.getRequestFailure() != null)
                    LOG.debug("Request Failure", result.getRequestFailure());
                if (result.getResponseFailure() != null)
                    LOG.debug("Response Failure", result.getResponseFailure());
            }

            Throwable failure = result.getFailure();
            if ((failure instanceof java.io.IOException) || (failure instanceof UpgradeException))
            {
                // handle as-is
                handleException(failure);
            }
            else
            {
                // wrap in UpgradeException 
                handleException(new UpgradeException(requestURI, responseStatusCode, responseLine, failure));
            }
            return;
        }

        if (responseStatusCode != HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            // Failed to upgrade (other reason)
            handleException(new UpgradeException(requestURI, responseStatusCode, "Failed to upgrade to websocket: Unexpected HTTP Response Status Code: " + responseLine));
        }
    }

    private void handleException(Throwable failure)
    {
        try
        {
            localEndpoint.onError(failure);
        }
        catch (Throwable t)
        {
            LOG.warn("Exception while notifying onError", t);
        }

        fut.completeExceptionally(failure);
    }

    @Override
    public ContentResponse send() throws InterruptedException, TimeoutException, ExecutionException
    {
        throw new RuntimeException("Working with raw ContentResponse is invalid for WebSocket");
    }

    @Override
    public void send(final CompleteListener listener)
    {
        initWebSocketHeaders();
        super.send(listener);
    }

    public CompletableFuture<Session> sendAsync()
    {
        send(this);
        return fut;
    }

    @Override
    public void upgrade(HttpResponse response, HttpConnectionOverHTTP oldConn)
    {
        if (!this.getHeaders().get(HttpHeader.UPGRADE).equalsIgnoreCase("websocket"))
        {
            // Not my upgrade
            throw new HttpResponseException("Not WebSocket Upgrade", response);
        }

        // Check the Accept hash
        String reqKey = this.getHeaders().get(HttpHeader.SEC_WEBSOCKET_KEY);
        String expectedHash = AcceptHash.hashKey(reqKey);
        String respHash = response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_ACCEPT);

        if (!expectedHash.equalsIgnoreCase(respHash))
        {
            throw new HttpResponseException("Invalid Sec-WebSocket-Accept hash", response);
        }

        // We can upgrade
        EndPoint endp = oldConn.getEndPoint();

        WebSocketClientConnection connection = new WebSocketClientConnection(endp, wsClient.getExecutor(), wsClient.getScheduler(), localEndpoint.getPolicy(),
            wsClient.getBufferPool());

        Collection<Connection.Listener> connectionListeners = wsClient.getBeans(Connection.Listener.class);

        if (connectionListeners != null)
        {
            connectionListeners.forEach((listener) ->
            {
                if (!(listener instanceof WebSocketSession))
                    connection.addListener(listener);
            });
        }

        URI requestURI = this.getURI();

        ClientUpgradeResponse upgradeResponse = new ClientUpgradeResponse(response);

        WebSocketSession session = getSessionFactory().createSession(requestURI, localEndpoint, connection);
        session.setUpgradeRequest(new ClientUpgradeRequest(this));
        session.setUpgradeResponse(upgradeResponse);
        connection.addListener(session);

        List<ExtensionConfig> extensions = new ArrayList<>();
        HttpField extField = response.getHeaders().getField(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        if (extField != null)
        {
            String[] extValues = extField.getValues();
            if (extValues != null)
            {
                for (String extVal : extValues)
                {
                    QuotedStringTokenizer tok = new QuotedStringTokenizer(extVal, ",");
                    while (tok.hasMoreTokens())
                    {
                        extensions.add(ExtensionConfig.parse(tok.nextToken()));
                    }
                }
            }
        }

        ExtensionStack extensionStack = new ExtensionStack(getExtensionFactory());
        extensionStack.negotiate(extensions);
        extensionStack.configure(connection.getParser());
        extensionStack.configure(connection.getGenerator());

        // Setup Incoming Routing
        connection.setNextIncomingFrames(extensionStack);
        extensionStack.setNextIncoming(session);

        // Setup Outgoing Routing
        session.setOutgoingHandler(extensionStack);
        extensionStack.setNextOutgoing(connection);

        session.addManaged(extensionStack);
        session.setFuture(fut);

        if (upgradeListener != null)
        {
            upgradeListener.onHandshakeResponse(upgradeResponse);
        }

        // Now swap out the connection
        endp.upgrade(connection);
    }

    public void setUpgradeListener(UpgradeListener upgradeListener)
    {
        this.upgradeListener = upgradeListener;
    }

    private HttpFields getHttpFields()
    {
        return super.getHeaders();
    }
}
