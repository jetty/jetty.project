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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;

@SuppressWarnings("unused")
public class ContentDocs
{
    // tag::echo[]
    void echo(Content.Source source, Content.Sink sink, Callback callback)
    {
        Callback echo = new Callback()
        {
            private Content.Chunk chunk;

            public void succeeded()
            {
                while (true)
                {
                    // release previous chunk
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

                    // read the next chunk
                    chunk = source.read();

                    if (chunk == null) // if no chunk, demand more
                        source.demand(this::succeeded);
                    else if (chunk.hasRemaining()) // if chunk has content, write it to the sink
                        sink.write(chunk.isLast(), chunk.getByteBuffer(), this);
                    else if (Content.Chunk.isError(chunk)) // if it is an error fail the callback
                        callback.failed(chunk.getCause());
                    else // otherwise continue with another chunk
                        continue;
                    return;
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

    public static void main(String... args) throws Exception
    {
        testEcho();
    }
}
