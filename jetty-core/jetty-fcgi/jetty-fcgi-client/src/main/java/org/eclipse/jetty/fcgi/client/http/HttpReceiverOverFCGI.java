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

package org.eclipse.jetty.fcgi.client.http;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
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

    void receive()
    {
        if (!hasContent())
        {
            HttpConnectionOverFCGI httpConnection = getHttpChannel().getHttpConnection();
            boolean setFillInterest = httpConnection.parseAndFill();
            if (!hasContent() && setFillInterest)
                httpConnection.fillInterested();
        }
        else
        {
            responseContentAvailable();
        }
    }

    @Override
    public void onInterim()
    {
        receive();
    }

    @Override
    public Content.Chunk read(boolean fillInterestIfNeeded)
    {
        Content.Chunk chunk = consumeChunk();
        if (chunk != null)
            return chunk;
        HttpConnectionOverFCGI httpConnection = getHttpChannel().getHttpConnection();
        boolean needFillInterest = httpConnection.parseAndFill();
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
        responseFailure(failure, Promise.from(failed ->
        {
            if (failed)
                getHttpChannel().getHttpConnection().close(failure);
        }, x -> getHttpChannel().getHttpConnection().close(failure)));
    }

    void content(Content.Chunk chunk)
    {
        if (this.chunk != null)
            throw new IllegalStateException();
        this.chunk = chunk;
        responseContentAvailable();
    }

    void end(HttpExchange exchange)
    {
        if (chunk != null)
            throw new IllegalStateException();
        chunk = Content.Chunk.EOF;
        responseSuccess(exchange, this::receiveNext);
    }

    private void receiveNext()
    {
        if (hasContent())
            throw new IllegalStateException();
        if (chunk != null)
            throw new IllegalStateException();

        HttpConnectionOverFCGI httpConnection = getHttpChannel().getHttpConnection();
        boolean setFillInterest = httpConnection.parseAndFill();
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
    protected void responseFailure(Throwable failure, Promise<Boolean> promise)
    {
        super.responseFailure(failure, promise);
    }
}
