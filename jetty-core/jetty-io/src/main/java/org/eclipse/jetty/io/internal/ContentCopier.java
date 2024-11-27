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

package org.eclipse.jetty.io.internal;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentCopier extends IteratingNestedCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentCopier.class);

    private final Content.Source source;
    private final Content.Sink sink;
    private final Content.Chunk.Processor chunkProcessor;
    private Content.Chunk chunk;
    private boolean terminated;

    public ContentCopier(Content.Source source, Content.Sink sink, Content.Chunk.Processor chunkProcessor, Callback callback)
    {
        super(callback);
        this.source = source;
        this.sink = sink;
        this.chunkProcessor = chunkProcessor;
    }

    @Override
    protected Action process() throws Throwable
    {
        if (terminated)
            return Action.SUCCEEDED;

        chunk = source.read();

        if (chunk == null)
        {
            source.demand(Invocable.from(getInvocationType(), this::succeeded));
            return Action.SCHEDULED;
        }

        if (chunkProcessor != null && chunkProcessor.process(chunk, this))
            return Action.SCHEDULED;

        terminated = chunk.isLast();

        if (Content.Chunk.isFailure(chunk))
        {
            failed(chunk.getFailure());
            return Action.SCHEDULED;
        }

        sink.write(chunk.isLast(), chunk.getByteBuffer(), this);
        return Action.SCHEDULED;
    }

    @Override
    protected void onSuccess()
    {
        chunk = Content.Chunk.releaseAndNext(chunk);
    }

    @Override
    protected void onFailure(Throwable cause)
    {
        ExceptionUtil.callAndThen(cause, source::fail, super::onFailure);
    }

    @Override
    protected void onCompleteFailure(Throwable x)
    {
        chunk = Content.Chunk.releaseAndNext(chunk);
    }
}
