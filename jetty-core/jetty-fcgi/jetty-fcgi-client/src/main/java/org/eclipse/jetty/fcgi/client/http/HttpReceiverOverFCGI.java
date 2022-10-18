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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.SerializedInvoker;

public class HttpReceiverOverFCGI extends HttpReceiver
{
    private final AtomicReference<Runnable> contentActionRef = new AtomicReference<>();
    private ContentSource contentSource;
    private Content.Chunk contentGenerated;

    public HttpReceiverOverFCGI(HttpChannel channel)
    {
        super(channel);
    }

    @Override
    protected void reset()
    {
        contentActionRef.set(null);
        contentSource = null;
        contentGenerated = null;
        super.reset();
    }

    void content(Content.Chunk chunk)
    {
        if (contentGenerated != null)
            throw new IllegalStateException();
        contentGenerated = chunk;

        if (contentSource == null)
        {
            Runnable r = contentActionRef.getAndSet(null);
            r.run();
            return;
        }

        contentSource.onDataAvailable();
    }

    @Override
    protected Content.Source newContentSource()
    {
        contentSource = new ContentSource();
        return contentSource;
    }

    private class ContentSource implements Content.Source
    {
        private final SerializedInvoker invoker = new SerializedInvoker();
        private volatile Content.Chunk currentChunk;
        private volatile Runnable demandCallback;

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = consumeCurrentChunk();
            if (chunk != null)
                return chunk;
            currentChunk = HttpReceiverOverFCGI.this.read(false);
            return consumeCurrentChunk();
        }

        public void onDataAvailable()
        {
            if (demandCallback != null)
                invoker.run(this::invokeDemandCallback);
        }

        private Content.Chunk consumeCurrentChunk()
        {
            if (currentChunk != null)
            {
                Content.Chunk rc = currentChunk;
                if (!(rc instanceof Content.Chunk.Error))
                    currentChunk = currentChunk.isLast() ? Content.Chunk.EOF : null;
                return rc;
            }
            return null;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (demandCallback == null)
                throw new IllegalArgumentException();
            if (this.demandCallback != null)
                throw new IllegalStateException();
            this.demandCallback = demandCallback;

            invoker.run(this::meetDemand);
        }

        private void meetDemand()
        {
            while (true)
            {
                if (currentChunk != null)
                {
                    invoker.run(this::invokeDemandCallback);
                    break;
                }
                else
                {
                    currentChunk = HttpReceiverOverFCGI.this.read(true);
                    if (currentChunk == null)
                        return;
                }
            }
        }

        private void invokeDemandCallback()
        {
            Runnable demandCallback = this.demandCallback;
            this.demandCallback = null;
            if (demandCallback != null)
            {
                try
                {
                    demandCallback.run();
                }
                catch (Throwable x)
                {
                    fail(x);
                }
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            if (currentChunk != null)
            {
                currentChunk.release();
                failAndClose(failure);
            }
            currentChunk = Content.Chunk.from(failure);
        }
    }

    private Content.Chunk read(boolean fillInterestIfNeeded)
    {
        Content.Chunk chunk = consumeContentGenerated();
        if (chunk != null)
            return chunk;
        HttpConnectionOverFCGI httpConnection = getHttpChannel().getHttpConnection();
        boolean contentGenerated = httpConnection.parseAndFill();
        if (!contentGenerated && fillInterestIfNeeded)
        {
            if (!httpConnection.isFillInterested())
                httpConnection.fillInterested();
        }
        return consumeContentGenerated();
    }

    private Content.Chunk consumeContentGenerated()
    {
        Content.Chunk chunk = this.contentGenerated;
        this.contentGenerated = null;
        return chunk;
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
    protected void responseSuccess(HttpExchange exchange)
    {
        if (contentGenerated != null)
            throw new IllegalStateException();
        contentGenerated = Content.Chunk.EOF;
        contentSource.onDataAvailable();
        super.responseSuccess(exchange);
    }

    @Override
    protected void responseFailure(Throwable failure)
    {
        super.responseFailure(failure);
    }

    protected void receive()
    {
        getHttpChannel().receive();
    }

    private void failAndClose(Throwable failure)
    {
        responseFailure(failure);
        HttpChannelOverFCGI httpChannel = getHttpChannel();
        httpChannel.getHttpConnection().close(failure);
    }
}
