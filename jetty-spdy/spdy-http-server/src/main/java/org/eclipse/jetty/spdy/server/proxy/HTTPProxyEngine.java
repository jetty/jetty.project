//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.server.proxy;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link HTTPProxyEngine} implements a SPDY to HTTP proxy, that is, converts SPDY events received by clients into
 * HTTP events for the servers.</p>
 */
public class HTTPProxyEngine extends ProxyEngine
{
    private static final Logger LOG = Log.getLogger(HTTPProxyEngine.class);
    private static final Callback LOGGING_CALLBACK = new LoggingCallback();

    private final HttpClient httpClient;

    public HTTPProxyEngine(HttpClient httpClient)
    {
        this.httpClient = httpClient;
        configureHttpClient(httpClient);
    }

    private void configureHttpClient(HttpClient httpClient)
    {
        // Redirects must be proxied as is, not followed
        httpClient.setFollowRedirects(false);
        // Must not store cookies, otherwise cookies of different clients will mix
        httpClient.setCookieStore(new HttpCookieStore.Empty());
    }

    public StreamFrameListener proxy(final Stream clientStream, SynInfo clientSynInfo, ProxyEngineSelector.ProxyServerInfo proxyServerInfo)
    {
        short version = clientStream.getSession().getVersion();
        String method = clientSynInfo.getHeaders().get(HTTPSPDYHeader.METHOD.name(version)).getValue();
        String path = clientSynInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version)).getValue();

        Fields headers = new Fields(clientSynInfo.getHeaders(), false);

        removeHopHeaders(headers);
        addRequestProxyHeaders(clientStream, headers);
        customizeRequestHeaders(clientStream, headers);

        String host = proxyServerInfo.getHost();
        int port = proxyServerInfo.getAddress().getPort();

        LOG.debug("Sending HTTP request to: {}", host + ":" + port);
        final Request request = httpClient.newRequest(host, port)
                .path(path)
                .method(HttpMethod.fromString(method));
        addNonSpdyHeadersToRequest(version, headers, request);

        if (!clientSynInfo.isClose())
        {
            request.content(new DeferredContentProvider());
        }

        sendRequest(clientStream, request);

        return new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // We proxy to HTTP so we do not receive replies
                throw new UnsupportedOperationException("Not Yet Implemented");
            }

            @Override
            public void onHeaders(Stream stream, HeadersInfo headersInfo)
            {
                throw new UnsupportedOperationException("Not Yet Implemented");
            }

            @Override
            public void onData(Stream clientStream, final DataInfo clientDataInfo)
            {
                LOG.debug("received clientDataInfo: {} for stream: {}", clientDataInfo, clientStream);

                DeferredContentProvider contentProvider = (DeferredContentProvider)request.getContent();
                contentProvider.offer(clientDataInfo.asByteBuffer(true));

                if (clientDataInfo.isClose())
                    contentProvider.close();
            }
        };
    }

    private void sendRequest(final Stream clientStream, Request request)
    {
        request.send(new Response.Listener.Adapter()
        {
            private volatile boolean committed;

            @Override
            public void onHeaders(final Response response)
            {
                LOG.debug("onHeaders called with response: {}. Sending replyInfo to client.", response);
                Fields responseHeaders = createResponseHeaders(clientStream, response);
                removeHopHeaders(responseHeaders);
                ReplyInfo replyInfo = new ReplyInfo(responseHeaders, false);
                clientStream.reply(replyInfo, new Callback.Adapter()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        LOG.debug("failed: ", x);
                        response.abort(x);
                    }

                    @Override
                    public void succeeded()
                    {
                        committed = true;
                    }
                });
            }

            @Override
            public void onContent(final Response response, ByteBuffer content)
            {
                LOG.debug("onContent called with response: {} and content: {}. Sending response content to client.",
                        response, content);
                final ByteBuffer contentCopy = httpClient.getByteBufferPool().acquire(content.remaining(), true);
                BufferUtil.flipPutFlip(content, contentCopy);
                ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(contentCopy, false);
                clientStream.data(dataInfo, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        LOG.debug("failed: ", x);
                        releaseBuffer();
                        response.abort(x);
                    }

                    @Override
                    public void succeeded()
                    {
                        releaseBuffer();
                    }

                    private void releaseBuffer()
                    {
                        httpClient.getByteBufferPool().release(contentCopy);
                    }
                });
            }

            @Override
            public void onSuccess(Response response)
            {
                LOG.debug("onSuccess called. Closing client stream.");
                clientStream.data(new ByteBufferDataInfo(BufferUtil.EMPTY_BUFFER, true), LOGGING_CALLBACK);
            }

            @Override
            public void onFailure(Response response, Throwable failure)
            {
                LOG.debug("onFailure called: ", failure);
                if (committed)
                {
                    LOG.debug("clientStream already committed. Resetting stream.");
                    try
                    {
                        clientStream.getSession().rst(new RstInfo(clientStream.getId(), StreamStatus.INTERNAL_ERROR));
                    }
                    catch (InterruptedException | ExecutionException | TimeoutException e)
                    {
                        LOG.debug(e);
                    }
                }
                else
                {
                    if (clientStream.isClosed())
                        return;
                    Fields responseHeaders = createResponseHeaders(clientStream, response);
                    if (failure instanceof TimeoutException)
                        responseHeaders.add(HTTPSPDYHeader.STATUS.name(clientStream.getSession().getVersion()),
                                String.valueOf(HttpStatus.GATEWAY_TIMEOUT_504));
                    else
                        responseHeaders.add(HTTPSPDYHeader.STATUS.name(clientStream.getSession().getVersion()),
                                String.valueOf(HttpStatus.BAD_GATEWAY_502));
                    ReplyInfo replyInfo = new ReplyInfo(responseHeaders, true);
                    clientStream.reply(replyInfo, LOGGING_CALLBACK);
                }
            }
        });
    }

    private Fields createResponseHeaders(Stream clientStream, Response response)
    {
        Fields responseHeaders = new Fields();
        for (HttpField header : response.getHeaders())
            responseHeaders.add(header.getName(), header.getValue());
            short version = clientStream.getSession().getVersion();
        if (response.getStatus() > 0)
            responseHeaders.add(HTTPSPDYHeader.STATUS.name(version),
                    String.valueOf(response.getStatus()));
        responseHeaders.add(HTTPSPDYHeader.VERSION.name(version), HttpVersion.HTTP_1_1.asString());
        addResponseProxyHeaders(clientStream, responseHeaders);
        return responseHeaders;
    }

    private void addNonSpdyHeadersToRequest(short version, Fields headers, Request request)
    {
        for (Fields.Field header : headers)
            if (HTTPSPDYHeader.from(version, header.getName()) == null)
                request.header(header.getName(), header.getValue());
    }

    static class LoggingCallback extends Callback.Adapter
    {
        @Override
        public void failed(Throwable x)
        {
            LOG.debug(x);
        }

        @Override
        public void succeeded()
        {
            LOG.debug("succeeded");
        }
    }
}
