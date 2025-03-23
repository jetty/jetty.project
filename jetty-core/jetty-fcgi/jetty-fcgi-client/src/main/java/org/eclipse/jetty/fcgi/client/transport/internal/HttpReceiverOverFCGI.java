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

package org.eclipse.jetty.fcgi.client.transport.internal;

import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Promise;

public class HttpReceiverOverFCGI extends HttpReceiver
{
    private Content.Chunk chunk;

    public HttpReceiverOverFCGI(HttpChannel channel)
    {
        super(channel);
    }

    private HttpConnectionOverFCGI getHttpConnection()
    {
        return getHttpChannel().getHttpConnection();
    }

    void receive()
    {
        if (!hasContent())
        {
            HttpConnectionOverFCGI httpConnection = getHttpConnection();
            boolean setFillInterest = httpConnection.parseAndFill(true);
            if (!hasContent() && setFillInterest)
                httpConnection.fillInterested();
        }
        else
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange != null)
                responseContentAvailable(exchange);
        }
    }

    @Override
    public void onInterim()
    {
        receive();
    }

    @Override
    protected void reset()
    {
        super.reset();
        if (chunk != null)
        {
            chunk.release();
            chunk = null;
        }
    }

    @Override
    protected void dispose()
    {
        super.dispose();
        if (chunk != null)
        {
            chunk.release();
            chunk = null;
        }
    }

    @Override
    public Content.Chunk read(boolean fillInterestIfNeeded)
    {
        Content.Chunk chunk = consumeChunk();
        if (chunk != null)
            return chunk;
        HttpConnectionOverFCGI httpConnection = getHttpConnection();
        boolean needFillInterest = httpConnection.parseAndFill(false);
        chunk = consumeChunk();
        if (chunk != null)
            return chunk;
        if (needFillInterest && fillInterestIfNeeded)
            httpConnection.fillInterested();
        return null;
    }

    private Content.Chunk consumeChunk()
    {
        Content.Chunk chunk = this.chunk;
        this.chunk = null;
        return chunk;
    }

    @Override
    public void failAndClose(Throwable failure)
    {
        HttpConnectionOverFCGI httpConnection = getHttpConnection();
        responseFailure(failure, Promise.from(failed ->
        {
            if (failed)
                httpConnection.close(failure);
        }, x -> httpConnection.close(failure)));
    }

    void content(Content.Chunk chunk)
    {
        if (this.chunk != null)
            throw new IllegalStateException();
        // Retain the chunk because it is stored for later reads.
        chunk.retain();
        this.chunk = chunk;
    }

    void end()
    {
        if (chunk != null)
            throw new IllegalStateException();
        chunk = Content.Chunk.EOF;
    }

    void responseSuccess(HttpExchange exchange)
    {
        super.responseSuccess(exchange, this::receiveNext);
    }

    private void receiveNext()
    {
        if (hasContent())
            throw new IllegalStateException();
        if (chunk != null)
            throw new IllegalStateException();

        HttpConnectionOverFCGI httpConnection = getHttpConnection();
        boolean setFillInterest = httpConnection.parseAndFill(true);
        if (!hasContent() && setFillInterest)
            httpConnection.fillInterested();
    }

    @Override
    protected HttpChannelOverFCGI getHttpChannel()
    {
        return (HttpChannelOverFCGI)super.getHttpChannel();
    }

    @Override
    protected void responseBegin(HttpExchange exchange)
    {
        super.responseBegin(exchange);
    }

    @Override
    protected void responseHeader(HttpExchange exchange, HttpField field)
    {
        super.responseHeader(exchange, field);
    }

    @Override
    protected void responseHeaders(HttpExchange exchange)
    {
        super.responseHeaders(exchange);
    }

    @Override
    protected void responseContentAvailable(HttpExchange exchange)
    {
        super.responseContentAvailable(exchange);
    }

    @Override
    protected void responseFailure(Throwable failure, Promise<Boolean> promise)
    {
        super.responseFailure(failure, promise);
    }
}
