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

package org.eclipse.jetty.websocket.common.test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionUpgrader;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;

public class BlockheadClientRequest extends HttpRequest implements Response.CompleteListener, HttpConnectionUpgrader
{
    private static final Logger LOG = Log.getLogger(BlockheadClientRequest.class);
    private final BlockheadClient client;
    private final CompletableFuture<BlockheadConnection> fut;

    protected BlockheadClientRequest(BlockheadClient client, URI uri)
    {
        super(client, new HttpConversation(), uri);
        this.client = client;
        this.fut = new CompletableFuture<>();
        getConversation().setAttribute(HttpConnectionUpgrader.class.getName(), this);
    }

    public void setInitialBytes(ByteBuffer initialBytes)
    {
        content(new RawBytesProvider(initialBytes));
    }

    private final String genRandomKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void initWebSocketHeaders()
    {
        method(HttpMethod.GET);
        version(HttpVersion.HTTP_1_1);

        HttpFields fields = getHeaders();

        // The Upgrade Headers
        if (!fields.contains(HttpHeader.UPGRADE))
            header(HttpHeader.UPGRADE, "websocket");
        if (!fields.contains(HttpHeader.CONNECTION))
            header(HttpHeader.CONNECTION, "Upgrade");

        // The WebSocket Headers
        if (!fields.contains(HttpHeader.SEC_WEBSOCKET_KEY))
            header(HttpHeader.SEC_WEBSOCKET_KEY, genRandomKey());
        if (!fields.contains(HttpHeader.SEC_WEBSOCKET_VERSION))
            header(HttpHeader.SEC_WEBSOCKET_VERSION, "13");

        // (Per the hybi list): Add no-cache headers to avoid compatibility issue.
        // There are some proxies that rewrite "Connection: upgrade"
        // to "Connection: close" in the response if a request doesn't contain
        // these headers.
        if (!fields.contains(HttpHeader.PRAGMA))
            header(HttpHeader.PRAGMA, "no-cache");
        if (!fields.contains(HttpHeader.CACHE_CONTROL))
            header(HttpHeader.CACHE_CONTROL, "no-cache");
    }

    @Override
    public ContentResponse send() throws InterruptedException, TimeoutException, ExecutionException
    {
        throw new RuntimeException("Working with raw ContentResponse is invalid for WebSocket");
    }

    @Override
    public void send(final Response.CompleteListener listener)
    {
        initWebSocketHeaders();
        super.send(listener);
    }

    public CompletableFuture<BlockheadConnection> sendAsync()
    {
        send(this);
        return fut;
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
            if ((failure instanceof java.net.ConnectException) || (failure instanceof UpgradeException))
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
            handleException(new UpgradeException(requestURI, responseStatusCode, responseLine));
        }
    }

    private void handleException(Throwable failure)
    {
        fut.completeExceptionally(failure);
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

        if (expectedHash.equalsIgnoreCase(respHash) == false)
        {
            throw new HttpResponseException("Invalid Sec-WebSocket-Accept hash", response);
        }

        // We can upgrade
        EndPoint endp = oldConn.getEndPoint();

        ExtensionStack extensionStack = new ExtensionStack(client.getExtensionFactory());
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
        extensionStack.negotiate(extensions);

        BlockheadClientConnection connection = new BlockheadClientConnection(
            client.getPolicy(),
            client.getBufferPool(),
            extensionStack,
            fut,
            endp,
            client.getExecutor());

        endp.setIdleTimeout(client.getIdleTimeout());

        connection.setUpgradeRequestHeaders(this.getHeaders());
        connection.setUpgradeResponseHeaders(response.getHeaders());

        // Now swap out the connection
        endp.upgrade(connection);
    }

    /**
     * Raw Bytes Content Provider (intentionally without a Content-Type)
     */
    public static class RawBytesProvider extends ByteBufferContentProvider
    {
        public RawBytesProvider(ByteBuffer buf)
        {
            super(buf);
        }

        @Override
        public String getContentType()
        {
            return null;
        }
    }
}
