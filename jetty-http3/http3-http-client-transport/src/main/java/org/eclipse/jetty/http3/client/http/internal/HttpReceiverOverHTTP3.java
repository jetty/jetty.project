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

package org.eclipse.jetty.http3.client.http.internal;

import java.io.UncheckedIOException;
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
    private volatile boolean notifySuccess;

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

        if (notifySuccess)
            responseSuccess(exchange);
        else
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

            notifySuccess = frame.isLast();
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
            }
        }
    }

    @Override
    public void onDataAvailable(Stream.Client stream)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        try
        {
            Stream.Data data = stream.readData();
            if (data != null)
            {
                ByteBuffer byteBuffer = data.getByteBuffer();
                if (byteBuffer.hasRemaining())
                {
                    if (data.isLast())
                        notifySuccess = true;

                    Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, data::complete, x ->
                    {
                        data.complete();
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
        catch (Throwable x)
        {
            Throwable failure = x;
            if (x instanceof UncheckedIOException)
                failure = x.getCause();
            exchange.getRequest().abort(failure);
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
        responseSuccess(exchange);
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

    @Override
    protected void reset()
    {
        super.reset();
        notifySuccess = false;
    }
}
