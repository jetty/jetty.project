//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.EOFException;

import org.eclipse.jetty.client.RetryableRequestException;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.RetryableStreamException;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP3 extends HttpReceiver
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP3.class);

    public HttpReceiverOverHTTP3(HttpChannelOverHTTP3 channel)
    {
        super(channel);
    }

    @Override
    protected void onInterim()
    {
    }

    @Override
    public Content.Chunk read(boolean fillInterestIfNeeded)
    {
        Stream stream = getHttpChannel().getStream();
        if (LOG.isDebugEnabled())
            LOG.debug("Reading, fillInterestIfNeeded={} from {} in {}", fillInterestIfNeeded, stream, this);
        if (stream == null)
            return Content.Chunk.from(new EOFException("Channel has been released"));
        Content.Chunk chunk = stream.read();
        if (LOG.isDebugEnabled())
            LOG.debug("Read stream data {} in {}", chunk, this);
        if (chunk == null)
        {
            if (fillInterestIfNeeded)
                stream.demand();
            return null;
        }
        if (!chunk.isLast() || chunk.hasRemaining())
        {
            // Convert to non-last chunk.
            return Content.Chunk.asChunk(chunk.getByteBuffer(), false, chunk);
        }
        chunk.release();
        if (Content.Chunk.isFailure(chunk))
        {
            responseFailure(chunk.getFailure(), Promise.noop());
            return chunk;
        }
        else
        {
            responseSuccess(null);
            return Content.Chunk.EOF;
        }
    }

    @Override
    public void failAndClose(Throwable failure)
    {
        Stream stream = getHttpChannel().getStream();
        responseFailure(failure, Promise.from(failed ->
        {
            if (failed)
                stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), failure, Promise.Invocable.noop());
        }, x -> stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), failure, Promise.Invocable.noop())));
    }

    @Override
    protected HttpChannelOverHTTP3 getHttpChannel()
    {
        return (HttpChannelOverHTTP3)super.getHttpChannel();
    }

    private HttpConnectionOverHTTP3 getHttpConnection()
    {
        return getHttpChannel().getHttpConnection();
    }

    Runnable onResponse(Stream.Client stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return null;

        return new Invocable.ReadyTask(getHttpConnection().getInvocationType(), () ->
        {
            HttpResponse httpResponse = exchange.getResponse();
            MetaData.Response response = (MetaData.Response)frame.getMetaData();
            httpResponse.version(response.getHttpVersion()).status(response.getStatus()).reason(response.getReason());

            responseBegin(exchange);

            HttpFields headers = response.getHttpFields();
            for (HttpField header : headers)
            {
                responseHeader(exchange, header);
            }

            // TODO: add support for HttpMethod.CONNECT.

            responseHeaders(exchange);
        });
    }

    Runnable onDataAvailable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Data available notification in {}", this);

        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return null;

        Runnable task = () -> responseContentAvailable(exchange);
        return new Invocable.ReadyTask(getInvocationType(), task);
    }

    Runnable onTrailer(HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return null;

        HttpFields trailers = frame.getMetaData().getHttpFields();
        trailers.forEach(exchange.getResponse()::trailer);

        Runnable task = () -> responseSuccess(null);
        return new Invocable.ReadyTask(getHttpConnection().getInvocationType(), task);
    }

    Runnable onIdleTimeout(Throwable failure, Promise<Boolean> promise)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
        {
            promise.succeeded(false);
            return null;
        }
        Runnable task = () -> Promise.completeWith(promise, exchange.getRequest().abort(failure));
        return new Invocable.ReadyTask(getHttpConnection().getInvocationType(), task);
    }

    Runnable onFailure(Throwable failure)
    {
        if (failure instanceof RetryableStreamException)
            failure = new RetryableRequestException(failure);
        Throwable f = failure;
        Runnable task = () -> responseFailure(f, Promise.noop());
        return new Invocable.ReadyTask(getHttpConnection().getInvocationType(), task);
    }
}
