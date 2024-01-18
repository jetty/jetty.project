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

package org.eclipse.jetty.io.content;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>A {@link Flow.Subscriber} that wraps a {@link Content.Sink}.
 * Content delivered to the {@link #onNext(Content.Chunk)} method is
 * written to {@link Content.Sink#write(boolean, ByteBuffer, Callback)} and the chunk
 * is released once the write collback is succeeded or failed.</p>
 */
public class ContentSinkSubscriber implements Flow.Subscriber<Content.Chunk>
{
    private final Content.Sink sink;
    private final Callback callback;
    private final AtomicInteger lastAndComplete = new AtomicInteger(2);
    private Flow.Subscription subscription;

    public ContentSinkSubscriber(Content.Sink sink, Callback callback)
    {
        this.sink = sink;
        this.callback = callback;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription)
    {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(Content.Chunk chunk)
    {
        // Retain the chunk because the write may not complete immediately.
        chunk.retain();
        sink.write(chunk.isLast(), chunk.getByteBuffer(), new Callback()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.getInvocationType(callback);
            }

            public void succeeded()
            {
                chunk.release();
                if (chunk.isLast())
                    onComplete();
                else
                    subscription.request(1);
            }

            public void failed(Throwable failure)
            {
                chunk.release();
                subscription.cancel();
                onError(failure);
            }
        });
    }

    @Override
    public void onError(Throwable failure)
    {
        callback.failed(failure);
    }

    @Override
    public void onComplete()
    {
        // wait until called twice: once from last write success and once from the publisher
        if (lastAndComplete.decrementAndGet() == 0)
            callback.succeeded();
    }
}
