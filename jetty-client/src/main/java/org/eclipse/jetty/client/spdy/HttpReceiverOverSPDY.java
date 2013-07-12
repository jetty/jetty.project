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

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

public class HttpReceiverOverSPDY extends HttpReceiver implements StreamFrameListener
{
    public HttpReceiverOverSPDY(HttpChannelOverSPDY channel)
    {
        super(channel);
    }

    @Override
    public HttpChannelOverSPDY getHttpChannel()
    {
        return (HttpChannelOverSPDY)super.getHttpChannel();
    }

    @Override
    public void onReply(Stream stream, ReplyInfo replyInfo)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpResponse response = exchange.getResponse();

        Fields fields = replyInfo.getHeaders();
        // TODO: use HTTPSPDYHeader enum
        HttpVersion version = HttpVersion.fromString(fields.get(":version").value());
        response.version(version);
        Integer status = fields.get(":status").valueAsInt();
        response.status(status);
        response.reason(HttpStatus.getMessage(status));

        onResponseBegin(exchange);

        for (Fields.Field field : fields)
        {
            // TODO: handle multiple values properly
            // TODO: skip special headers
            HttpField httpField = new HttpField(field.name(), field.value());
            onResponseHeader(exchange, httpField);
        }

        onResponseHeaders(exchange);

        if (replyInfo.isClose())
        {
            onResponseSuccess(exchange);
        }
    }

    @Override
    public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
    {
        getHttpChannel().getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new Callback.Adapter());
        return null;
    }

    @Override
    public void onHeaders(Stream stream, HeadersInfo headersInfo)
    {
        // TODO: see above handling of headers
    }

    @Override
    public void onData(Stream stream, DataInfo dataInfo)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        int length = dataInfo.length();
        // TODO: avoid data copy here
        onResponseContent(exchange, dataInfo.asByteBuffer(false));
        dataInfo.consume(length);

        if (dataInfo.isClose())
        {
            onResponseSuccess(exchange);
        }
    }
}
