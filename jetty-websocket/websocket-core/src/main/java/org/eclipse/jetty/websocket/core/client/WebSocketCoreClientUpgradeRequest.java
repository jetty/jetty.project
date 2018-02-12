//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionUpgrader;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AcceptHash;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.UpgradeException;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

public abstract class WebSocketCoreClientUpgradeRequest extends HttpRequest implements Response.CompleteListener, HttpConnectionUpgrader
{
    public static class Static extends WebSocketCoreClientUpgradeRequest
    {
        private final FrameHandler frameHandler;

        public Static(WebSocketCoreClient webSocketClient, URI requestURI, FrameHandler frameHandler)
        {
            super(webSocketClient, requestURI);
            this.frameHandler = frameHandler;
        }

        @Override
        public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, WebSocketPolicy upgradePolicy, HttpResponse response)
        {
            return frameHandler;
        }
    }

    private static final Logger LOG = Log.getLogger(WebSocketCoreClientUpgradeRequest.class);
    protected final CompletableFuture<FrameHandler.Channel> fut;
    private final WebSocketCoreClient wsClient;
    private List<UpgradeListener> upgradeListeners = new ArrayList<>();
    /** Offered Extensions */
    private List<ExtensionConfig> extensions = new ArrayList<>();
    /** Offered SubProtocols */
    private List<String> subProtocols = new ArrayList<>();

    public WebSocketCoreClientUpgradeRequest(WebSocketCoreClient webSocketClient, URI requestURI)
    {
        super(webSocketClient.getHttpClient(), new HttpConversation(), requestURI);
        this.wsClient = webSocketClient;
        this.fut = new CompletableFuture<>();
        method(HttpMethod.GET);
        version(HttpVersion.HTTP_1_1);
    }

    public void addListener(UpgradeListener listener)
    {
        upgradeListeners.add(listener);
    }

    public void addExtensions(ExtensionConfig... configs)
    {
        for (ExtensionConfig config : configs)
        {
            this.extensions.add(config);
        }
        updateWebSocketExtensionHeader();
    }

    public void addExtensions(String... configs)
    {
        this.extensions.addAll(ExtensionConfig.parseList(configs));
        updateWebSocketExtensionHeader();
    }

    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    public void setExtensions(List<ExtensionConfig> configs)
    {
        this.extensions = configs;
        updateWebSocketExtensionHeader();
    }

    public List<String> getSubProtocols()
    {
        return this.subProtocols;
    }

    public void setSubProtocols(String... protocols)
    {
        this.subProtocols.clear();
        this.subProtocols.addAll(Arrays.asList(protocols));
        updateWebSocketSubProtocolHeader();
    }

    public void setSubProtocols(List<String> protocols)
    {
        this.subProtocols.clear();
        this.subProtocols.addAll(protocols);
        updateWebSocketSubProtocolHeader();
    }

    @Override
    public void send(final Response.CompleteListener listener)
    {
        initWebSocketHeaders();
        super.send(listener);
    }

    public CompletableFuture<FrameHandler.Channel> sendAsync()
    {
        send(this);
        return fut;
    }

    @SuppressWarnings("Duplicates")
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

    protected void handleException(Throwable failure)
    {
        fut.completeExceptionally(failure);
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void upgrade(HttpResponse response, HttpConnectionOverHTTP httpConnection)
    {
        if (!this.getHeaders().get(HttpHeader.UPGRADE).equalsIgnoreCase("websocket"))
        {
            // Not my upgrade
            throw new HttpResponseException("Not a WebSocket Upgrade", response);
        }

        HttpClient httpClient = wsClient.getHttpClient();

        // Check the Accept hash
        String reqKey = this.getHeaders().get(HttpHeader.SEC_WEBSOCKET_KEY);
        String expectedHash = AcceptHash.hashKey(reqKey);
        String respHash = response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_ACCEPT);

        if (expectedHash.equalsIgnoreCase(respHash) == false)
        {
            throw new HttpResponseException("Invalid Sec-WebSocket-Accept hash (was:" + respHash + ", expected:" + expectedHash + ")", response);
        }

        // Verify the Negotiated Extensions
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

        // Create unique WebSocketPolicy for this specific upgrade
        WebSocketPolicy upgradePolicy = wsClient.getPolicy().clonePolicy();

        extensionStack.negotiate(wsClient.getObjectFactory(), upgradePolicy, httpClient.getByteBufferPool(), extensions);

        // Check the negotiated subprotocol
        String negotiatedSubProtocol = null;
        HttpField subProtocolField = response.getHeaders().getField(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        if (subProtocolField != null)
        {
            String values[] = subProtocolField.getValues();
            if (values != null)
            {
                if (values.length > 1)
                {
                    throw new WebSocketException("Too many WebSocket subprotocol's in response: " + values);
                }
                else if (values.length == 1)
                {
                    negotiatedSubProtocol = values[0];
                }
            }
        }

        if (!subProtocols.isEmpty() && !subProtocols.contains(negotiatedSubProtocol))
        {
            throw new WebSocketException("Upgrade failed: subprotocol [" + negotiatedSubProtocol + "] not found in offered subprotocols " + subProtocols);
        }

        // We can upgrade
        EndPoint endp = httpConnection.getEndPoint();
        customize(endp);

        FrameHandler frameHandler = getFrameHandler(wsClient, upgradePolicy, response);

        if (frameHandler == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("FrameHandler is null for request ").append(this.getURI().toASCIIString());
            if (negotiatedSubProtocol != null)
            {
                err.append(" [subprotocol: ").append(negotiatedSubProtocol).append("]");
            }
            throw new WebSocketException(err.toString());
        }

        WebSocketChannel wsChannel = newWebSocketChannel(frameHandler, upgradePolicy, extensionStack, negotiatedSubProtocol);
        WebSocketConnection wsConnection = newWebSocketConnection(endp, httpClient.getExecutor(), httpClient.getByteBufferPool(), wsChannel);
        wsChannel.setWebSocketConnection(wsConnection);

        notifyUpgradeListeners((listener) -> listener.onHandshakeResponse(this, response));

        // Now swap out the connection
        endp.upgrade(wsConnection);

        fut.complete(wsChannel);
    }

    /**
     * Allow for overridden customization of endpoint (such as special transport level properties: e.g. TCP keepAlive)
     * @see <a href="https://github.com/eclipse/jetty.project/issues/1811">Issue #1811 - Customization of WebSocket Connections via WebSocketPolicy</a>
     */
    protected void customize(EndPoint endp)
    {
    }

    protected WebSocketConnection newWebSocketConnection(EndPoint endp, Executor executor, ByteBufferPool byteBufferPool, WebSocketChannel wsChannel)
    {
        return new WebSocketConnection(endp, executor, byteBufferPool, wsChannel);
    }

    protected WebSocketChannel newWebSocketChannel(FrameHandler handler,
                                                   WebSocketPolicy upgradePolicy,
                                                   ExtensionStack extensionStack,
                                                   String negotiatedSubProtocol)
    {
        return new WebSocketChannel(handler, upgradePolicy, extensionStack, negotiatedSubProtocol);
    }

    public abstract FrameHandler getFrameHandler(WebSocketCoreClient coreClient, WebSocketPolicy upgradePolicy, HttpResponse response);

    private final String genRandomKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new String(B64Code.encode(bytes));
    }

    private void initWebSocketHeaders()
    {
        method(HttpMethod.GET);
        version(HttpVersion.HTTP_1_1);

        // The Upgrade Headers
        setHeaderIfNotPresent(HttpHeader.UPGRADE, "websocket");
        setHeaderIfNotPresent(HttpHeader.CONNECTION, "Upgrade");

        // The WebSocket Headers
        setHeaderIfNotPresent(HttpHeader.SEC_WEBSOCKET_KEY, genRandomKey());
        setHeaderIfNotPresent(HttpHeader.SEC_WEBSOCKET_VERSION, "13");

        // (Per the hybi list): Add no-cache headers to avoid compatibility issue.
        // There are some proxies that rewrite "Connection: upgrade"
        // to "Connection: close" in the response if a request doesn't contain
        // these headers.
        setHeaderIfNotPresent(HttpHeader.PRAGMA, "no-cache");
        setHeaderIfNotPresent(HttpHeader.CACHE_CONTROL, "no-cache");

        // Notify upgrade hooks
        notifyUpgradeListeners((listener) -> listener.onHandshakeRequest(this));
    }

    private void setHeaderIfNotPresent(HttpHeader header, String value)
    {
        if (!getHeaders().contains(header))
        {
            getHeaders().put(header, value);
        }
    }

    private void notifyUpgradeListeners(Consumer<UpgradeListener> action)
    {
        for (UpgradeListener listener : upgradeListeners)
        {
            try
            {
                action.accept(listener);
            }
            catch (Throwable t)
            {
                LOG.warn("Unhandled error: " + t.getMessage(), t);
            }
        }
    }

    private void updateWebSocketExtensionHeader()
    {
        HttpFields headers = getHeaders();
        headers.remove(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        for (ExtensionConfig config : extensions)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, config.getParameterizedName());
        }
    }

    private void updateWebSocketSubProtocolHeader()
    {
        HttpFields headers = getHeaders();
        headers.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        for (String protocol : subProtocols)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
        }
    }
}
