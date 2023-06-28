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

package org.eclipse.jetty.docs.programming;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class ContentDocs
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentDocs.class);

    // tag::echo[]
    public void echo(Content.Source source, Content.Sink sink, Callback callback)
    {
        Callback echo = new Callback()
        {
            private Content.Chunk chunk;

            public void succeeded()
            {
                // release any previous chunk
                if (chunk != null)
                {
                    chunk.release();
                    // complete if it was the last
                    if (chunk.isLast())
                    {
                        callback.succeeded();
                        return;
                    }
                }

                while (true)
                {
                    // read the next chunk
                    chunk = source.read();

                    if (chunk == null)
                    {
                        // if no chunk, demand more and call succeeded when demand is met.
                        source.demand(this::succeeded);
                        return;
                    }

                    if (Content.Chunk.isFailure(chunk, true))
                    {
                        // if it is a persistent failure, then fail the callback
                        callback.failed(chunk.getFailure());
                        return;
                    }

                    if (chunk.hasRemaining() || chunk.isLast())
                    {
                        // if chunk has content or is last, write it to the sink and resume this loop in callback
                        sink.write(chunk.isLast(), chunk.getByteBuffer(), this);
                        return;
                    }

                    chunk.release();
                }
            }

            public void failed(Throwable x)
            {
                source.fail(x);
                callback.failed(x);
            }
        };
        source.demand(echo::succeeded);
    }
    // tag::echo[]

    public static void testEcho() throws Exception
    {
        AsyncContent source = new AsyncContent();
        AsyncContent sink = new AsyncContent();

        Callback.Completable echoCallback = new Callback.Completable();
        new ContentDocs().echo(source, sink, echoCallback);

        Content.Chunk chunk = sink.read();
        if (chunk != null)
            throw new IllegalStateException("No chunk expected yet");

        FutureCallback onContentAvailable = new FutureCallback();
        sink.demand(onContentAvailable::succeeded);
        if (onContentAvailable.isDone())
            throw new IllegalStateException("No demand expected yet");

        Callback.Completable writeCallback = new Callback.Completable();
        Content.Sink.write(source, false, "One", writeCallback);
        if (writeCallback.isDone())
            throw new IllegalStateException("Should wait until first chunk is consumed");

        onContentAvailable.get();
        chunk = sink.read();
        if (!"One".equals(BufferUtil.toString(chunk.getByteBuffer())))
            throw new IllegalStateException("first chunk is expected");

        if (writeCallback.isDone())
            throw new IllegalStateException("Should wait until first chunk is consumed");
        chunk.release();
        writeCallback.get();


        writeCallback = new Callback.Completable();
        Content.Sink.write(source, true, "Two", writeCallback);
        if (writeCallback.isDone())
            throw new IllegalStateException("Should wait until second chunk is consumed");

        onContentAvailable = new FutureCallback();
        sink.demand(onContentAvailable::succeeded);
        if (!onContentAvailable.isDone())
            throw new IllegalStateException("Demand expected for second chunk");

        chunk = sink.read();
        if (!"Two".equals(BufferUtil.toString(chunk.getByteBuffer())))
            throw new IllegalStateException("second chunk is expected");
        chunk.release();
        writeCallback.get();

        onContentAvailable = new FutureCallback();
        sink.demand(onContentAvailable::succeeded);
        if (!onContentAvailable.isDone())
            throw new IllegalStateException("Demand expected for EOF");

        chunk = sink.read();
        if (!chunk.isLast())
            throw new IllegalStateException("EOF expected");
    }

    public static class FutureString extends CompletableFuture<String>
    {
        private final CharsetStringBuilder text;
        private final Content.Source source;

        public FutureString(Content.Source source, Charset charset)
        {
            this.source = source;
            this.text = CharsetStringBuilder.forCharset(charset);
            source.demand(this::onContentAvailable);
        }

        private void onContentAvailable()
        {
            while (true)
            {
                Content.Chunk chunk = source.read();
                if (chunk == null)
                {
                    source.demand(this::onContentAvailable);
                    return;
                }

                try
                {
                    if (Content.Chunk.isFailure(chunk))
                        throw chunk.getFailure();

                    if (chunk.hasRemaining())
                        text.append(chunk.getByteBuffer());

                    if (chunk.isLast() && complete(text.build()))
                        return;
                }
                catch (Throwable e)
                {
                    completeExceptionally(e);
                }
                finally
                {
                    chunk.release();
                }
            }
        }
    }

    public static void testFutureString() throws Exception
    {
        AsyncContent source = new AsyncContent();
        FutureString future = new FutureString(source, StandardCharsets.UTF_8);
        if (future.isDone())
            throw new IllegalStateException();

        Callback.Completable writeCallback = new Callback.Completable();
        Content.Sink.write(source, false, "One", writeCallback);
        if (!writeCallback.isDone() || future.isDone())
            throw new IllegalStateException("Should be consumed");
        Content.Sink.write(source, false, "Two", writeCallback);
        if (!writeCallback.isDone() || future.isDone())
            throw new IllegalStateException("Should be consumed");
        Content.Sink.write(source, true, "Three", writeCallback);
        if (!writeCallback.isDone() || !future.isDone())
            throw new IllegalStateException("Should be consumed");

        System.err.println(future.get());

    }

    public static void main(String... args) throws Exception
    {
        testEcho();
        testFutureString();
    }
}
