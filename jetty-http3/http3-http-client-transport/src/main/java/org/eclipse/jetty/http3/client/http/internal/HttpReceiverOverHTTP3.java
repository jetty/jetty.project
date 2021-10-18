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

package org.eclipse.jetty.http3.client.http.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

public class HttpReceiverOverHTTP3 extends HttpReceiver implements Stream.Listener
{
    protected HttpReceiverOverHTTP3(HttpChannelOverHTTP3 channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverHTTP3 getHttpChannel()
    {
        return (HttpChannelOverHTTP3)super.getHttpChannel();
    }

    @Override
    public void onResponse(Stream stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpResponse httpResponse = exchange.getResponse();
        MetaData.Response response = (MetaData.Response)frame.getMetaData();
        httpResponse.version(response.getHttpVersion()).status(response.getStatus()).reason(response.getReason());

        if (responseBegin(exchange))
        {
            HttpFields headers = response.getFields();
            for (HttpField header : headers)
            {
                if (!responseHeader(exchange, header))
                    return;
            }

            // TODO: add support for HttpMethod.CONNECT.

            if (responseHeaders(exchange))
            {
                int status = response.getStatus();
                boolean informational = HttpStatus.isInformational(status) && status != HttpStatus.SWITCHING_PROTOCOLS_101;
                if (frame.isLast() || informational)
                    responseSuccess(exchange);
                else
                    stream.demand();
            }
            else
            {
                if (frame.isLast())
                {
                    // There is no demand to trigger response success, so add
                    // a poison pill to trigger it when there will be demand.
                    // TODO
//                    notifyContent(exchange, new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
                }
            }
        }
    }

    @Override
    protected void receive()
    {
        // Called when the application resumes demand of content.
        // TODO: stream.demand() should be enough.
    }

    @Override
    public void onDataAvailable(Stream stream)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        Stream.Data data = stream.readData();
        if (data != null)
        {
            ByteBuffer byteBuffer = data.getByteBuffer();
            if (byteBuffer.hasRemaining())
            {
                // TODO: callback failure should invoke responseFailure().
                Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, data::complete);
                boolean proceed = responseContent(exchange, byteBuffer, callback);
                if (proceed)
                {
                    if (data.isLast())
                        responseSuccess(exchange);
                    else
                        stream.demand();
                }
            }
            else
            {
                data.complete();
                if (data.isLast())
                    responseSuccess(exchange);
                else
                    stream.demand();
            }
        }
        else
        {
            stream.demand();
        }
    }

    @Override
    public void onTrailer(Stream stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpFields trailers = frame.getMetaData().getFields();
        trailers.forEach(exchange.getResponse()::trailer);
        // Previous DataFrames had endStream=false, so
        // add a poison pill to trigger response success
        // after all normal DataFrames have been consumed.
        // TODO
//        notifyContent(exchange, new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
    }
}
