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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;

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
        // Always set last=false because we do the last write from onComplete().
        sink.write(false, chunk.getByteBuffer(), Callback.from(() -> succeeded(chunk), x -> failed(chunk, x)));
    }

    private void succeeded(Content.Chunk chunk)
    {
        chunk.release();
        if (!chunk.isLast())
            subscription.request(1);
    }

    private void failed(Content.Chunk chunk, Throwable failure)
    {
        chunk.release();
        subscription.cancel();
        onError(failure);
    }

    @Override
    public void onError(Throwable failure)
    {
        callback.failed(failure);
    }

    @Override
    public void onComplete()
    {
        sink.write(true, null, callback);
    }
}
