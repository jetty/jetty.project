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
import org.eclipse.jetty.util.IteratingNestedCallback;
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
        if (terminated)
            return Action.SUCCEEDED;

        current = source.read();

        if (current == null)
        {
            source.demand(this::iterate);
            return Action.IDLE;
        }

        if (chunkProcessor != null && chunkProcessor.process(current, this))
            return Action.SCHEDULED;

        if (Content.Chunk.isError(current))
        {
            if (current.isLast())
                throw current.getError();
            if (LOG.isDebugEnabled())
                LOG.debug("ignored warning", current.getError());
            succeeded();
            return Action.SCHEDULED;
        }

        sink.write(current.isLast(), current.getByteBuffer(), this);
        return Action.SCHEDULED;
    }

    @Override
    public void succeeded()
    {
        terminated = current.isLast();
        current.release();
        current = null;
        super.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        if (current != null)
            current.release();
        current = null;
        source.fail(x);
        super.failed(x);
    }
}
