//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.client.http;

import java.net.URI;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
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
    protected void sendHeaders(HttpExchange exchange, final HttpContent content, final Callback callback)
    {
        HttpRequest request = exchange.getRequest();
        String path = relativize(request.getPath());
        HttpURI uri = HttpURI.createHttpURI(request.getScheme(), request.getHost(), request.getPort(), path, null, request.getQuery(), null);
        MetaData.Request metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_2, request.getHeaders());
        Supplier<HttpFields> trailerSupplier = request.getTrailers();
        metaData.setTrailerSupplier(trailerSupplier);

        HeadersFrame headersFrame;
        Promise<Stream> promise;
        if (content.hasContent())
        {
            headersFrame = new HeadersFrame(metaData, null, false);
            promise = new HeadersPromise(request, callback)
            {
                @Override
                public void succeeded(Stream stream)
                {
                    super.succeeded(stream);
                    if (expects100Continue(request))
                    {
                        // Don't send the content yet.
                        callback.succeeded();
                    }
                    else
                    {
                        boolean advanced = content.advance();
                        boolean lastContent = content.isLast();
                        if (advanced || lastContent)
                            sendContent(stream, content, trailerSupplier, callback);
                        else
                            callback.succeeded();
                    }
                }
            };
        }
        else
        {
            HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
            boolean endStream = trailers == null || trailers.size() == 0;
            headersFrame = new HeadersFrame(metaData, null, endStream);
            promise = new HeadersPromise(request, callback)
            {
                @Override
                public void succeeded(Stream stream)
                {
                    super.succeeded(stream);
                    if (endStream)
                        callback.succeeded();
                    else
                        sendTrailers(stream, trailers, callback);
                }
            };
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
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        if (content.isConsumed())
        {
            // The superclass calls sendContent() one more time after the last content.
            // This is necessary for HTTP/1.1 to generate the terminal chunk (with trailers),
            // but it's not necessary for HTTP/2 so we just succeed the callback.
            callback.succeeded();
        }
        else
        {
            Stream stream = getHttpChannel().getStream();
            Supplier<HttpFields> trailerSupplier = exchange.getRequest().getTrailers();
            sendContent(stream, content, trailerSupplier, callback);
        }
    }

    private void sendContent(Stream stream, HttpContent content, Supplier<HttpFields> trailerSupplier, Callback callback)
    {
        boolean lastContent = content.isLast();
        HttpFields trailers = null;
        boolean endStream = false;
        if (lastContent)
        {
            trailers = trailerSupplier == null ? null : trailerSupplier.get();
            endStream = trailers == null || trailers.size() == 0;
        }
        DataFrame dataFrame = new DataFrame(stream.getId(), content.getByteBuffer(), endStream);
        HttpFields fTrailers = trailers;
        stream.data(dataFrame, endStream || !lastContent ? callback : Callback.from(() -> sendTrailers(stream, fTrailers, callback), callback::failed));
    }

    private void sendTrailers(Stream stream, HttpFields trailers, Callback callback)
    {
        MetaData metaData = new MetaData(HttpVersion.HTTP_2, trailers);
        HeadersFrame trailersFrame = new HeadersFrame(stream.getId(), metaData, null, true);
        stream.headers(trailersFrame, callback);
    }

    private class HeadersPromise implements Promise<Stream>
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
            HttpChannelOverHTTP2 channel = getHttpChannel();
            channel.setStream(stream);
            ((IStream)stream).setAttachment(channel);
            long idleTimeout = request.getIdleTimeout();
            if (idleTimeout >= 0)
                stream.setIdleTimeout(idleTimeout);
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
        }
    }
}
