//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
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
        Request request = exchange.getRequest();
        String path = relativize(request.getPath());
        HttpURI uri = new HttpURI(request.getScheme(), request.getHost(), request.getPort(), path, null, request.getQuery(), null);
        MetaData.Request metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_2, request.getHeaders());
        HeadersFrame headersFrame = new HeadersFrame(metaData, null, !content.hasContent());
        HttpChannelOverHTTP2 channel = getHttpChannel();
        Promise<Stream> promise = new Promise<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                getHttpChannel().setStream(stream);
                stream.setIdleTimeout(request.getIdleTimeout());

                if (content.hasContent() && !expects100Continue(request))
                {
                    boolean advanced = content.advance();
                    boolean lastContent = content.isLast();
                    if (advanced || lastContent)
                    {
                        DataFrame dataFrame = new DataFrame(stream.getId(), content.getByteBuffer(), lastContent);
                        stream.data(dataFrame, callback);
                        return;
                    }
                }
                callback.succeeded();
            }

            @Override
            public void failed(Throwable failure)
            {
                callback.failed(failure);
            }
        };
        // TODO optimize the send of HEADERS and DATA frames.
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
            callback.succeeded();
        }
        else
        {
            Stream stream = getHttpChannel().getStream();
            DataFrame frame = new DataFrame(stream.getId(), content.getByteBuffer(), content.isLast());
            stream.data(frame, callback);
        }
    }
}
