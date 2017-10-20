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

package org.eclipse.jetty.websocket.client;

import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.impl.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.client.impl.WebSocketClientConnection;
import org.eclipse.jetty.websocket.common.WebSocketSessionImpl;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.handshake.AcceptHash;
import org.eclipse.jetty.websocket.core.UpgradeException;

public class WebSocketUpgradeRequest extends HttpRequest implements UpgradeRequest, CompleteListener, HttpConnectionUpgrader
{
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeRequest.class);
    private final CompletableFuture<Session> fut;
    private final WebSocketClient wsClient;
    private List<ExtensionConfig> extensions = new ArrayList<>();
    private List<String> subProtocols = new ArrayList<>();
    /**
     * The User provider raw Endpoint Instance
     */
    private Object endpointInstance;
    private UpgradeListener upgradeListener;

    public WebSocketUpgradeRequest(WebSocketClient webSocketClient, URI requestURI)
    {
        super(webSocketClient.getHttpClient(), new HttpConversation(), requestURI);
        this.wsClient = webSocketClient;
        this.fut = new CompletableFuture<>();
    }

    /**
     * Exists for internal use of HttpClient by WebSocketClient.
     * <p>
     * Maintained for Backward compatibility and also for JSR356 WebSocket ClientContainer use.
     *
     * @param wsClient the WebSocketClient that this request uses
     * @param upgradeRequest the ClientUpgradeRequest to base this request from
     */
    public WebSocketUpgradeRequest(WebSocketClient wsClient, UpgradeRequest upgradeRequest)
    {
        super(wsClient.getHttpClient(), new HttpConversation(), upgradeRequest.getRequestURI());

        // Initialize Self from Upgrade Request
        upgradeRequest.getHeaderMap().forEach((name, values) ->
                values.forEach((value) -> header(name, value))
        );

        upgradeRequest.getCookies().forEach((cookie) -> cookie(cookie));

        this.extensions.addAll(upgradeRequest.getExtensions());
        updateWebSocketExtensionHeader();

        this.subProtocols.addAll(upgradeRequest.getSubProtocols());
        updateWebSocketSubProtocolHeader();

        // Validate URI
        if (!getURI().isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be an absolute URI: " + getURI());
        }

        String scheme = getURI().getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss")))
        {
            throw new IllegalArgumentException("WebSocket URI must use 'ws' or 'wss' scheme: " + getURI());
        }

        // Validate WebSocket Client
        this.wsClient = wsClient;
        if (!this.wsClient.isStarted())
        {
            throw new IllegalStateException("WebSocketClient not started");
        }

        this.fut = new CompletableFuture<>();
    }

    @Override
    public void addExtensions(ExtensionConfig... configs)
    {
        for (ExtensionConfig config : configs)
        {
            this.extensions.add(config);
        }
        updateWebSocketExtensionHeader();
    }

    @Override
    public void addExtensions(String... configs)
    {
        this.extensions.addAll(ExtensionConfig.parseList(configs));
        updateWebSocketExtensionHeader();
    }

    public EndPoint configure(EndPoint endp)
    {
        return endp;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        this.extensions = configs;
        updateWebSocketExtensionHeader();
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
    public Map<String, List<String>> getHeaderMap()
    {
        // TODO
        return null;
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
    public void setHttpVersion(String httpVersion)
    {
        super.version(HttpVersion.fromString(httpVersion));
    }

    @Override
    public Map<String, List<String>> getParameterMap()
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
        return super.getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        return super.getURI();
    }

    @Override
    public void setRequestURI(URI uri)
    {
        throw new UnsupportedOperationException("Cannot reset/change RequestURI (use constructor)");
    }

    @Override
    public List<String> getSubProtocols()
    {
        return this.subProtocols;
    }

    @Override
    public void setSubProtocols(String... protocols)
    {
        this.subProtocols.clear();
        this.subProtocols.addAll(Arrays.asList(protocols));
        updateWebSocketSubProtocolHeader();
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        return getSubProtocols().contains(test);
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
            if ((failure instanceof java.net.SocketException) ||
                    (failure instanceof java.io.InterruptedIOException) ||
                    (failure instanceof HttpResponseException) ||
                    (failure instanceof UpgradeException))
            {
                // handle as-is
                handleException(failure);
            }
            else
            {
                // wrap in UpgradeException
                handleException(new UpgradeException(requestURI, responseStatusCode, responseLine, failure));
            }
        }

        if (responseStatusCode != HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            // Failed to upgrade (other reason)
            handleException(new HttpResponseException("Not a 101 Switching Protocols Response: " + responseLine, response));
        }
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
    public void setCookies(List<HttpCookie> cookies)
    {
        for (HttpCookie cookie : cookies)
        {
            super.cookie(cookie);
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
    public void setMethod(String method)
    {
        super.method(method);
    }

    @Override
    public void setSubProtocols(List<String> protocols)
    {
        this.subProtocols = protocols;
        updateWebSocketSubProtocolHeader();
    }

    public void setUpgradeListener(UpgradeListener upgradeListener)
    {
        this.upgradeListener = upgradeListener;
    }

    public void setWebSocket(Object websocket)
    {
        this.endpointInstance = websocket;
    }

    @Override
    public void upgrade(HttpResponse response, HttpConnectionOverHTTP oldConn)
    {
        if (!this.getHeaders().get(HttpHeader.UPGRADE).equalsIgnoreCase("websocket"))
        {
            // Not my upgrade
            throw new HttpResponseException("Not a WebSocket Upgrade", response);
        }

        // Check the Accept hash
        String reqKey = this.getHeaders().get(HttpHeader.SEC_WEBSOCKET_KEY);
        String expectedHash = AcceptHash.hashKey(reqKey);
        String respHash = response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_ACCEPT);

        if (expectedHash.equalsIgnoreCase(respHash) == false)
        {
            throw new HttpResponseException("Invalid Sec-WebSocket-Accept hash", response);
        }

        ExtensionStack extensionStack = new ExtensionStack(wsClient.getExtensionRegistry());
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

        DecoratedObjectFactory objectFactory = wsClient.getObjectFactory();
        HttpClient httpClient = wsClient.getHttpClient();
        extensionStack.negotiate(objectFactory, wsClient.getPolicy(), httpClient.getByteBufferPool(), extensions);

        // We can upgrade
        EndPoint endp = oldConn.getEndPoint();

        endp = configure(endp);

        WebSocketClientConnection connection = new WebSocketClientConnection(
                endp,
                httpClient.getExecutor(),
                httpClient.getByteBufferPool(),
                objectFactory,
                wsClient.getPolicy(),
                extensionStack,
                this,
                new ClientUpgradeResponse(response));

        WebSocketSessionImpl session = wsClient.createSession(connection, endpointInstance);
        wsClient.notifySessionListeners((listener -> listener.onCreated(session)));

        session.addManaged(extensionStack);
        session.setFuture(fut);
        wsClient.addManaged(session);

        if (upgradeListener != null)
        {
            upgradeListener.onHandshakeResponse(new ClientUpgradeResponse(response));
        }

        // Now swap out the connection
        endp.upgrade(connection);
    }

    private final String genRandomKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new String(B64Code.encode(bytes));
    }

    private HttpFields getHttpFields()
    {
        return super.getHeaders();
    }

    private void handleException(Throwable failure)
    {
        fut.completeExceptionally(failure);
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

        if (upgradeListener != null)
        {
            upgradeListener.onHandshakeRequest(this);
        }
    }

    private void updateWebSocketExtensionHeader()
    {
        HttpFields headers = getHttpFields();
        headers.remove(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        for (ExtensionConfig config : extensions)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, config.getParameterizedName());
        }
    }

    private void updateWebSocketSubProtocolHeader()
    {
        HttpFields headers = getHttpFields();
        headers.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        for (String protocol : subProtocols)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
        }
    }
}
