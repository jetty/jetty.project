//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.client.transport.internal;

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
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP3 extends HttpReceiver implements Stream.Client.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP3.class);

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
    protected void receive()
    {
        // Called when the application resumes demand of content.
        if (LOG.isDebugEnabled())
            LOG.debug("resuming response processing on {}", this);

        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        getHttpChannel().getStream().demand();
    }

    @Override
    public void onResponse(Stream.Client stream, HeadersFrame frame)
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
                if (frame.isLast() || HttpStatus.isInterim(status))
                    responseSuccess(exchange);
                else
                    stream.demand();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling response processing, no demand after headers on {}", this);

                // TODO: If frame.isLast(), calling demand() on it will kinda of make little sense.
                //  we should go back to notifySuccessRef being set before this block (to avoid the race)
                //  and in receive() query it to call notifySuccess().

            }
        }
    }

    @Override
    public void onDataAvailable(Stream.Client stream)
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
                Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, data::release, x ->
                {
                    data.release();
                    if (responseFailure(x))
                        stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
                });
                boolean proceed = responseContent(exchange, byteBuffer, callback);
                if (proceed)
                {
                    if (data.isLast())
                        responseSuccess(exchange);
                    else
                        stream.demand();
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("stalling response processing, no demand after {} on {}", data, this);
                }
            }
            else
            {
                data.release();
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
    public void onTrailer(Stream.Client stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpFields trailers = frame.getMetaData().getFields();
        trailers.forEach(exchange.getResponse()::trailer);
    }

    @Override
    public boolean onIdleTimeout(Stream.Client stream, Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        return !exchange.abort(failure);
    }

    @Override
    public void onFailure(Stream.Client stream, long error, Throwable failure)
    {
        responseFailure(failure);
    }
}
