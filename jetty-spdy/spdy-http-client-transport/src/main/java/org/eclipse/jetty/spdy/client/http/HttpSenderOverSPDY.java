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

package org.eclipse.jetty.spdy.client.http;

import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;

public class HttpSenderOverSPDY extends HttpSender
{
    private volatile Stream stream;

    public HttpSenderOverSPDY(HttpChannelOverSPDY channel)
    {
        super(channel);
    }

    @Override
    public HttpChannelOverSPDY getHttpChannel()
    {
        return (HttpChannelOverSPDY)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, final HttpContent content, final Callback callback)
    {
        final Request request = exchange.getRequest();
        final long idleTimeout = request.getIdleTimeout();
        short spdyVersion = getHttpChannel().getSession().getVersion();
        Fields fields = new Fields();
        HttpField hostHeader = null;
        for (HttpField header : request.getHeaders())
        {
            String name = header.getName();
            // The host header needs a special treatment
            if (HTTPSPDYHeader.from(spdyVersion, name) != HTTPSPDYHeader.HOST)
                fields.add(name, header.getValue());
            else
                hostHeader = header;
        }

        // Add special SPDY headers
        fields.put(HTTPSPDYHeader.METHOD.name(spdyVersion), request.getMethod());
        String path = request.getPath();
        String query = request.getQuery();
        if (query != null)
            path += "?" + query;
        fields.put(HTTPSPDYHeader.URI.name(spdyVersion), path);
        fields.put(HTTPSPDYHeader.VERSION.name(spdyVersion), request.getVersion().asString());
        if (hostHeader != null)
            fields.put(HTTPSPDYHeader.HOST.name(spdyVersion), hostHeader.getValue());

        SynInfo synInfo = new SynInfo(fields, !content.hasContent());
        getHttpChannel().getSession().syn(synInfo, getHttpChannel().getHttpReceiver(), new Promise<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(idleTimeout);
                if (content.hasContent())
                    HttpSenderOverSPDY.this.stream = stream;
                callback.succeeded();
            }

            @Override
            public void failed(Throwable failure)
            {
                callback.failed(failure);
            }
        });
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
            ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(content.getByteBuffer(), content.isLast());
            stream.data(dataInfo, callback);
        }
    }

    @Override
    protected void reset()
    {
        super.reset();
        stream = null;
    }
}
