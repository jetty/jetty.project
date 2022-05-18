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

package org.eclipse.jetty.io.content;

import java.util.concurrent.Flow;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;

public class ContentSinkSubscriber implements Flow.Subscriber<Content.Chunk>
{
    private final Content.Sink sink;
    private Flow.Subscription subscription;

    public ContentSinkSubscriber(Content.Sink sink)
    {
        this.sink = sink;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription)
    {
        this.subscription = subscription;
    }

    @Override
    public void onNext(Content.Chunk item)
    {
        sink.write(item, Callback.from(Callback.NOOP::succeeded, x -> subscription.cancel()));
    }

    @Override
    public void onError(Throwable throwable)
    {
        sink.write(Content.Chunk.from(throwable), Callback.NOOP);
    }

    @Override
    public void onComplete()
    {
        sink.write(Content.Chunk.EOF, Callback.NOOP);
    }
}
