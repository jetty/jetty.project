//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;

public class HttpReceiverOverHTTP2 extends HttpReceiver implements Stream.Listener
{
    public HttpReceiverOverHTTP2(HttpChannel channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverHTTP2 getHttpChannel()
    {
        return (HttpChannelOverHTTP2)super.getHttpChannel();
    }

    @Override
    public void onHeaders(Stream stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpResponse response = exchange.getResponse();
        MetaData.Response metaData = (MetaData.Response)frame.getMetaData();
        response.version(metaData.getVersion()).status(metaData.getStatus()).reason(metaData.getReason());

        if (responseBegin(exchange))
        {
            HttpFields headers = metaData.getFields();
            for (HttpField header : headers)
            {
                if (!responseHeader(exchange, header))
                    return;
            }

            if (responseHeaders(exchange))
            {
                if (frame.isEndStream())
                    responseSuccess(exchange);
            }
        }
    }

    @Override
    public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
    {
        // Not supported.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.REFUSED_STREAM_ERROR.code), Callback.Adapter.INSTANCE);
        return null;
    }

    @Override
    public void onData(Stream stream, DataFrame frame, Callback callback)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        if (responseContent(exchange, frame.getData(), callback))
        {
            if (frame.isEndStream())
                responseSuccess(exchange);
        }
    }

    @Override
    public void onReset(Stream stream, ResetFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        ErrorCode error = ErrorCode.from(frame.getError());
        String reason = error == null ? "reset" : error.name().toLowerCase();
        exchange.getRequest().abort(new IOException(reason));
    }

    @Override
    public void onTimeout(Stream stream, Throwable failure)
    {
        responseFailure(failure);
    }
}
