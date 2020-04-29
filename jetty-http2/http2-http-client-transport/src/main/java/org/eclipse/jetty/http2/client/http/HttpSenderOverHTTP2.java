//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
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
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpSenderOverHTTP2 extends HttpSender
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpSenderOverHTTP2.class);

    public HttpSenderOverHTTP2(HttpChannelOverHTTP2 channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverHTTP2 getHttpChannel()
    {
        return (HttpChannelOverHTTP2)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, final Callback callback)
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
            metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_2, request.getHeaders(), -1, request.getTrailers());
        }

        HeadersFrame headersFrame;
        Promise<Stream> promise;
        if (isTunnel)
        {
            headersFrame = new HeadersFrame(metaData, null, false);
            promise = new HeadersPromise(request, callback, stream -> callback.succeeded());
        }
        else
        {
            Supplier<HttpFields> trailerSupplier = request.getTrailers();
            if (BufferUtil.isEmpty(contentBuffer) && lastContent)
            {
                HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
                boolean endStream = trailers == null || trailers.size() == 0;
                headersFrame = new HeadersFrame(metaData, null, endStream);
                promise = new HeadersPromise(request, callback, stream ->
                {
                    if (endStream)
                        callback.succeeded();
                    else
                        sendTrailers(stream, trailers, callback);
                });
            }
            else
            {
                headersFrame = new HeadersFrame(metaData, null, false);
                promise = new HeadersPromise(request, callback, stream ->
                    sendContent(stream, contentBuffer, lastContent, trailerSupplier, callback));
            }
        }
        // TODO optimize the send of HEADERS and DATA frames.
        HttpChannelOverHTTP2 channel = getHttpChannel();
        channel.getSession().newStream(headersFrame, promise, channel.getStreamListener());
    }

    private String relativize(String path)
    {
        try
        {
            String result = path;
            URI uri = URI.create(result);
            if (uri.isAbsolute())
                result = uri.getPath();
            return result.isEmpty() ? "/" : result;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not relativize " + path);
            return path;
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        Stream stream = getHttpChannel().getStream();
        Supplier<HttpFields> trailerSupplier = exchange.getRequest().getTrailers();
        sendContent(stream, contentBuffer, lastContent, trailerSupplier, callback);
    }

    private void sendContent(Stream stream, ByteBuffer buffer, boolean lastContent, Supplier<HttpFields> trailerSupplier, Callback callback)
    {
        boolean hasContent = buffer.hasRemaining();
        if (lastContent)
        {
            // Call the trailers supplier as late as possible.
            HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
            boolean hasTrailers = trailers != null && trailers.size() > 0;
            if (hasContent)
            {
                DataFrame dataFrame = new DataFrame(stream.getId(), buffer, !hasTrailers);
                Callback dataCallback = callback;
                if (hasTrailers)
                    dataCallback = Callback.from(() -> sendTrailers(stream, trailers, callback), callback::failed);
                stream.data(dataFrame, dataCallback);
            }
            else
            {
                if (hasTrailers)
                {
                    sendTrailers(stream, trailers, callback);
                }
                else
                {
                    DataFrame dataFrame = new DataFrame(stream.getId(), buffer, true);
                    stream.data(dataFrame, callback);
                }
            }
        }
        else
        {
            if (hasContent)
            {
                DataFrame dataFrame = new DataFrame(stream.getId(), buffer, false);
                stream.data(dataFrame, callback);
            }
            else
            {
                // Don't send empty non-last content.
                callback.succeeded();
            }
        }
    }

    private void sendTrailers(Stream stream, HttpFields trailers, Callback callback)
    {
        MetaData metaData = new MetaData(HttpVersion.HTTP_2, trailers);
        HeadersFrame trailersFrame = new HeadersFrame(stream.getId(), metaData, null, true);
        stream.headers(trailersFrame, callback);
    }

    private static class HeadersPromise implements Promise<Stream>
    {
        private final HttpRequest request;
        private final Callback callback;
        private final Consumer<Stream> succeed;

        private HeadersPromise(HttpRequest request, Callback callback, Consumer<Stream> succeed)
        {
            this.request = request;
            this.callback = callback;
            this.succeed = succeed;
        }

        @Override
        public void succeeded(Stream stream)
        {
            long idleTimeout = request.getIdleTimeout();
            if (idleTimeout >= 0)
                stream.setIdleTimeout(idleTimeout);
            succeed.accept(stream);
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
        }
    }
}
