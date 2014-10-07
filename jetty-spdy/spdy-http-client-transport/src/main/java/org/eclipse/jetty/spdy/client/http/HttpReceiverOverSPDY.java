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
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
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

        try
        {
            HttpResponse response = exchange.getResponse();

            Fields fields = replyInfo.getHeaders();
            short spdy = stream.getSession().getVersion();
            HttpVersion version = HttpVersion.fromString(fields.get(HTTPSPDYHeader.VERSION.name(spdy)).getValue());
            response.version(version);
            String[] status = fields.get(HTTPSPDYHeader.STATUS.name(spdy)).getValue().split(" ", 2);

            Integer code = Integer.parseInt(status[0]);
            response.status(code);
            String reason = status.length < 2 ? HttpStatus.getMessage(code) : status[1];
            response.reason(reason);

            if (responseBegin(exchange))
            {
                for (Fields.Field field : fields)
                {
                    String name = field.getName();
                    if (HTTPSPDYHeader.from(spdy, name) != null)
                        continue;
                    // TODO: handle multiple values properly
                    HttpField httpField = new HttpField(name, field.getValue());
                    responseHeader(exchange, httpField);
                }

                if (responseHeaders(exchange))
                {
                    if (replyInfo.isClose())
                    {
                        responseSuccess(exchange);
                    }
                }
            }
        }
        catch (Exception x)
        {
            responseFailure(x);
        }
    }

    @Override
    public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
    {
        // SPDY push not supported
        getHttpChannel().getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), Callback.Adapter.INSTANCE);
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

        try
        {
            int length = dataInfo.length();
            // TODO: avoid data copy here
            // TODO: handle callback properly
            boolean process = responseContent(exchange, dataInfo.asByteBuffer(false), new Callback.Adapter());
            dataInfo.consume(length);

            if (process)
            {
                if (dataInfo.isClose())
                {
                    responseSuccess(exchange);
                }
            }
        }
        catch (Exception x)
        {
            responseFailure(x);
        }
    }

    @Override
    public void onFailure(Stream stream, Throwable x)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;
        exchange.getRequest().abort(x);
    }
}
