//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.spdy;

import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
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
    protected void sendHeaders(HttpExchange exchange, final HttpContent content)
    {
        final Request request = exchange.getRequest();

        Fields fields = new Fields();
        HttpFields headers = request.getHeaders();
        for (HttpField header : headers)
            fields.add(header.getName(), header.getValue());

        SynInfo synInfo = new SynInfo(fields, !content.hasContent());
        getHttpChannel().getSession().syn(synInfo, getHttpChannel().getHttpReceiver(), new Promise<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                if (content.hasContent())
                    HttpSenderOverSPDY.this.stream = stream;
                content.succeeded();
            }

            @Override
            public void failed(Throwable failure)
            {
                content.failed(failure);
            }
        });
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content)
    {
        assert stream != null;
        ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(content.getByteBuffer(), content.isLast());
        stream.data(dataInfo, content);
    }

    @Override
    protected void reset()
    {
        super.reset();
        stream = null;
    }
}
