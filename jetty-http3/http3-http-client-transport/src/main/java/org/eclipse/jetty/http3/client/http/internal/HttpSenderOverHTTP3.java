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

package org.eclipse.jetty.http3.client.http.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HttpSenderOverHTTP3 extends HttpSender
{
    public HttpSenderOverHTTP3(HttpChannelOverHTTP3 channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverHTTP3 getHttpChannel()
    {
        return (HttpChannelOverHTTP3)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        HttpRequest request = exchange.getRequest();
        boolean isTunnel = HttpMethod.CONNECT.is(request.getMethod());
        MetaData.Request metaData;
        if (isTunnel)
        {
            String upgradeProtocol = request.getUpgradeProtocol();
            if (upgradeProtocol == null)
            {
                metaData = new MetaData.ConnectRequest((String)null, new HostPortHttpField(request.getPath()), null, request.getHeaders(), null);
            }
            else
            {
                HostPortHttpField authority = new HostPortHttpField(request.getHost(), request.getPort());
                metaData = new MetaData.ConnectRequest(request.getScheme(), authority, request.getPath(), request.getHeaders(), upgradeProtocol);
            }
        }
        else
        {
            String path = relativize(request.getPath());
            HttpURI uri = HttpURI.build()
                .scheme(request.getScheme())
                .host(request.getHost())
                .port(request.getPort())
                .path(path)
                .query(request.getQuery());
            metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_3, request.getHeaders(), -1, request.getTrailers());
        }

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailerFrame = null;

        if (isTunnel)
        {
            headersFrame = new HeadersFrame(metaData, false);
        }
        else
        {
            boolean hasContent = BufferUtil.hasContent(contentBuffer);
            if (hasContent)
            {
                headersFrame = new HeadersFrame(metaData, false);
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers(request);
                    boolean hasTrailers = trailers != null;
                    dataFrame = new DataFrame(contentBuffer, !hasTrailers);
                    if (hasTrailers)
                        trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                }
                else
                {
                    dataFrame = new DataFrame(contentBuffer, false);
                }
            }
            else
            {
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers(request);
                    boolean hasTrailers = trailers != null;
                    headersFrame = new HeadersFrame(metaData, !hasTrailers);
                    if (hasTrailers)
                        trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                }
                else
                {
                    headersFrame = new HeadersFrame(metaData, false);
                }
            }
        }

        HeadersFrame hf = headersFrame;
        DataFrame df = dataFrame;
        HeadersFrame tf = trailerFrame;

        HTTP3SessionClient session = getHttpChannel().getSession();
        CompletableFuture<Stream> completable = session.newRequest(hf, getHttpChannel().getStreamListener())
            .thenApply(stream -> onNewStream(stream, request));
        if (df != null)
            completable = completable.thenCompose(stream -> stream.data(df));
        if (tf != null)
            completable = completable.thenCompose(stream -> stream.trailer(tf));
        callback.completeWith(completable);
    }

    private Stream onNewStream(Stream stream, HttpRequest request)
    {
        getHttpChannel().setStream(stream);
        long idleTimeout = request.getIdleTimeout();
        if (idleTimeout > 0)
            ((HTTP3Stream)stream).setIdleTimeout(idleTimeout);
        return stream;
    }

    private HttpFields retrieveTrailers(HttpRequest request)
    {
        Supplier<HttpFields> trailerSupplier = request.getTrailers();
        HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
        return trailers == null || trailers.size() == 0 ? null : trailers;
    }

    @Override
    protected void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        Stream stream = getHttpChannel().getStream();
        boolean hasContent = contentBuffer.hasRemaining();
        if (lastContent)
        {
            // Call the trailers supplier as late as possible.
            HttpFields trailers = retrieveTrailers(exchange.getRequest());
            boolean hasTrailers = trailers != null && trailers.size() > 0;
            if (hasContent)
            {
                DataFrame dataFrame = new DataFrame(contentBuffer, !hasTrailers);
                CompletableFuture<Stream> completable;
                if (hasTrailers)
                    completable = stream.data(dataFrame).thenCompose(s -> sendTrailer(s, trailers));
                else
                    completable = stream.data(dataFrame);
                callback.completeWith(completable);
            }
            else
            {
                CompletableFuture<Stream> completable;
                if (hasTrailers)
                    completable = sendTrailer(stream, trailers);
                else
                    completable = stream.data(new DataFrame(contentBuffer, true));
                callback.completeWith(completable);
            }
        }
        else
        {
            if (hasContent)
            {
                CompletableFuture<Stream> completable = stream.data(new DataFrame(contentBuffer, false));
                callback.completeWith(completable);
            }
            else
            {
                // Don't send empty non-last content.
                callback.succeeded();
            }
        }
    }

    private CompletableFuture<Stream> sendTrailer(Stream stream, HttpFields trailers)
    {
        MetaData metaData = new MetaData(HttpVersion.HTTP_3, trailers);
        HeadersFrame trailerFrame = new HeadersFrame(metaData, true);
        return stream.trailer(trailerFrame);
    }
}
