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
    private Content.Chunk current;
    private boolean terminated;

    public ContentCopier(Content.Source source, Content.Sink sink, Content.Chunk.Processor chunkProcessor, Callback callback)
    {
        super(callback);
        this.source = source;
        this.sink = sink;
        this.chunkProcessor = chunkProcessor;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }

    @Override
    protected Action process() throws Throwable
    {
        if (current != null)
            current.release();

        if (terminated)
            return Action.SUCCEEDED;

        current = source.read();

        if (current == null)
        {
            source.demand(Invocable.from(getInvocationType(), this::succeeded));
            return Action.SCHEDULED;
        }

        if (chunkProcessor != null && chunkProcessor.process(current, this))
            return Action.SCHEDULED;

        terminated = current.isLast();

        if (Content.Chunk.isFailure(current))
        {
            failed(current.getFailure());
            return Action.SCHEDULED;
        }

        sink.write(current.isLast(), current.getByteBuffer(), this);
        return Action.SCHEDULED;
    }

    @Override
    protected void onCompleteFailure(Throwable x)
    {
        if (current != null)
        {
            current.release();
            current = Content.Chunk.next(current);
        }
        ExceptionUtil.callAndThen(x, source::fail, super::onCompleteFailure);
    }
}
