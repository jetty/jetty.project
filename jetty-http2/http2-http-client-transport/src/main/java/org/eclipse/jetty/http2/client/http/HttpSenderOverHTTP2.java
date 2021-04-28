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
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        HttpRequest request = exchange.getRequest();
        String path = relativize(request.getPath());
        HttpURI uri = HttpURI.createHttpURI(request.getScheme(), request.getHost(), request.getPort(), path, null, request.getQuery(), null);
        MetaData.Request metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_2, request.getHeaders());
        metaData.setTrailerSupplier(request.getTrailers());

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        if (content.hasContent())
        {
            headersFrame = new HeadersFrame(metaData, null, false);
            if (!expects100Continue(request))
            {
                boolean advanced = content.advance();
                boolean lastContent = content.isLast();
                if (advanced)
                {
                    if (lastContent)
                    {
                        HttpFields trailers = retrieveTrailers(request);
                        boolean hasTrailers = trailers != null;
                        dataFrame = new DataFrame(content.getByteBuffer(), !hasTrailers);
                        if (hasTrailers)
                            trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                    }
                    else
                    {
                        dataFrame = new DataFrame(content.getByteBuffer(), false);
                    }
                }
                else if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers(request);
                    if (trailers != null)
                        trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                    else
                        dataFrame = new DataFrame(BufferUtil.EMPTY_BUFFER, true);
                }
            }
        }
        else
        {
            HttpFields trailers = retrieveTrailers(request);
            boolean hasTrailers = trailers != null;
            headersFrame = new HeadersFrame(metaData, null, !hasTrailers);
            if (hasTrailers)
                trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null, true);
        }

        HttpChannelOverHTTP2 channel = getHttpChannel();
        IStream.FrameList frameList = new IStream.FrameList(headersFrame, dataFrame, trailersFrame);
        ((ISession)channel.getSession()).newStream(frameList, new HeadersPromise(request, callback), channel.getStreamListener());
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

    private HttpFields retrieveTrailers(HttpRequest request)
    {
        Supplier<HttpFields> trailerSupplier = request.getTrailers();
        HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
        return trailers == null || trailers.size() == 0 ? null : trailers;
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
            sendContent(getHttpChannel().getStream(), exchange.getRequest(), content, callback);
        }
    }

    private void sendContent(Stream stream, HttpRequest request, HttpContent content, Callback callback)
    {
        if (content.isLast())
        {
            HttpFields trailers = retrieveTrailers(request);
            boolean hasTrailers = trailers != null;
            DataFrame dataFrame = new DataFrame(stream.getId(), content.getByteBuffer(), !hasTrailers);
            if (hasTrailers)
                stream.data(dataFrame, Callback.from(() -> sendTrailers(stream, trailers, callback), callback::failed));
            else
                stream.data(dataFrame, callback);
        }
        else
        {
            DataFrame dataFrame = new DataFrame(stream.getId(), content.getByteBuffer(), false);
            stream.data(dataFrame, callback);
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
