//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.nio.ByteBuffer;
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
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

public class HttpSenderOverHTTP2 extends HttpSender
{
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
            metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_2, request.getHeaders(), -1, request.getTrailers());
        }

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        if (isTunnel)
        {
            headersFrame = new HeadersFrame(metaData, null, false);
        }
        else
        {
            boolean hasContent = BufferUtil.hasContent(contentBuffer);
            if (hasContent)
            {
                headersFrame = new HeadersFrame(metaData, null, false);
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers(request);
                    boolean hasTrailers = trailers != null;
                    dataFrame = new DataFrame(contentBuffer, !hasTrailers);
                    if (hasTrailers)
                        trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null, true);
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
                    headersFrame = new HeadersFrame(metaData, null, !hasTrailers);
                    if (hasTrailers)
                        trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                }
                else
                {
                    headersFrame = new HeadersFrame(metaData, null, false);
                }
            }
        }

        HttpChannelOverHTTP2 channel = getHttpChannel();
        IStream.FrameList frameList = new IStream.FrameList(headersFrame, dataFrame, trailersFrame);
        ((ISession)channel.getSession()).newStream(frameList, new HeadersPromise(request, callback), channel.getStreamListener());
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
                DataFrame dataFrame = new DataFrame(stream.getId(), contentBuffer, !hasTrailers);
                if (hasTrailers)
                    stream.data(dataFrame, Callback.from(() -> sendTrailers(stream, trailers, callback), callback::failed));
                else
                    stream.data(dataFrame, callback);
            }
            else
            {
                if (hasTrailers)
                {
                    sendTrailers(stream, trailers, callback);
                }
                else
                {
                    DataFrame dataFrame = new DataFrame(stream.getId(), contentBuffer, true);
                    stream.data(dataFrame, callback);
                }
            }
        }
        else
        {
            if (hasContent)
            {
                DataFrame dataFrame = new DataFrame(stream.getId(), contentBuffer, false);
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

        private HeadersPromise(HttpRequest request, Callback callback)
        {
            this.request = request;
            this.callback = callback;
        }

        @Override
        public void succeeded(Stream stream)
        {
            long idleTimeout = request.getIdleTimeout();
            if (idleTimeout >= 0)
                stream.setIdleTimeout(idleTimeout);
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
        }
    }
}
