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
import java.util.concurrent.ThreadLocalRandom;

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
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

public class WebSocketClientUpgradeRequest extends HttpRequest implements Response.CompleteListener, HttpConnectionUpgrader
{
    private static final Logger LOG = Log.getLogger(WebSocketClientUpgradeRequest.class);
    private final CompletableFuture<WebSocketChannel> fut;
    private final WebSocketClient wsClient;
    private final FrameHandler frameHandler;
    /** Offered Extensions */
    private List<ExtensionConfig> extensions = new ArrayList<>();
    /** Offered SubProtocols */
    private List<String> subProtocols = new ArrayList<>();

    protected WebSocketClientUpgradeRequest(WebSocketClient webSocketClient, URI requestURI, FrameHandler frameHandler)
    {
        super(webSocketClient.getHttpClient(), new HttpConversation(), requestURI);
        this.wsClient = webSocketClient;
        this.fut = new CompletableFuture<>();
        this.frameHandler = frameHandler;
    }

    /**
     * Override point for customization of Jetty EndPoint in use by WebSocketClient
     *
     * @param endp the endpoint to configure
     * @return the configured endpoint
     */
    public EndPoint configure(EndPoint endp)
    {
        return endp;
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

    public FrameHandler getFrameHandler()
    {
        return frameHandler;
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

    @Override
    public void send(final Response.CompleteListener listener)
    {
        initWebSocketHeaders();
        super.send(listener);
    }

    public CompletableFuture<WebSocketChannel> sendAsync()
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

    private void handleException(Throwable failure)
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
            throw new HttpResponseException("Invalid Sec-WebSocket-Accept hash", response);
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

        extensionStack.negotiate(wsClient.getObjectFactory(), wsClient.getPolicy(), httpClient.getByteBufferPool(), extensions);

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
        endp = configure(endp);

        WebSocketChannel wsChannel = new WebSocketChannel(frameHandler, wsClient.getPolicy(), extensionStack, negotiatedSubProtocol);

        WebSocketConnection wsConnection = new WebSocketConnection(
                endp,
                httpClient.getExecutor(),
                httpClient.getByteBufferPool(),
                wsChannel);

        wsChannel.setWebSocketConnection(wsConnection);

        //wsClient.addManaged(wsChannel); // TODO: or should this be the connection?

        // TODO: need way to hook into the post-upgraded response
        // if (upgradeListener != null)
        //    upgradeListener.onHandshakeResponse(new ClientUpgradeResponse(response));

        // Now swap out the connection
        endp.upgrade(wsConnection);

        fut.complete(wsChannel);
    }

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

        // TODO: need way to hook into pre-upgrade request
        // if (upgradeListener != null)
        //    upgradeListener.onHandshakeRequest(this);
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
