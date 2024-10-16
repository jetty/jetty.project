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

package org.eclipse.jetty.compression;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EncoderSink implements Content.Sink
{
    private final Content.Sink sink;

    protected EncoderSink(Content.Sink sink)
    {
        this.sink = sink;
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        try
        {
            if (!canEncode(last, content))
            {
                callback.succeeded();
                return;
            }
        }
        catch (Throwable t)
        {
            // TODO: do we need to tell the delegate that we failed?
            callback.failed(t);
            return;
        }

        if (content != null || last)
            new EncodeBufferCallback(last, content, callback).iterate();
        else
            callback.succeeded();
    }

    /**
     * Figure out if the encoding can be done with the provided content.
     *
     * @param last the last write.
     * @param content the content of the write event.
     * @return true if the {@link #encode(boolean, ByteBuffer)} should proceed.
     */
    protected boolean canEncode(boolean last, ByteBuffer content)
    {
        return true;
    }

    protected abstract WriteRecord encode(boolean last, ByteBuffer content);

    protected void release()
    {
    }

    public record WriteRecord(boolean last, ByteBuffer output, Callback callback) {}

    private class EncodeBufferCallback extends IteratingNestedCallback
    {
        private enum State
        {
            // Intial state, nothing has been attempted yet
            INITIAL,
            // We have started compressing
            COMPRESSING,
            // The last content is being encoded and is being flushed
            FINISHING,
            // The final content has been send (final state)
            FINISHED
        }

        private static final Logger LOG = LoggerFactory.getLogger(EncodeBufferCallback.class);
        private final AtomicReference<State> state = new AtomicReference<>(State.INITIAL);
        private final ByteBuffer content;
        private final boolean last;

        public EncodeBufferCallback(boolean last, ByteBuffer content, Callback callback)
        {
            super(callback);
            this.content = content;
            this.last = last;
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s last=%b]",
                super.toString(),
                BufferUtil.toDetailString(content),
                last
            );
        }

        protected void finished()
        {
            state.set(State.FINISHED);
            release();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("On Complete Failure", x);
            release();
            super.onCompleteFailure(x);
        }

        @Override
        protected void onCompleteSuccess()
        {
            super.onCompleteSuccess();
        }

        @Override
        protected Action process() throws Throwable
        {
            if (state.get() == State.FINISHED)
                return Action.SUCCEEDED;

            // Attempt to encode the next write event
            WriteRecord writeRecord = encode(last, content);
            if (writeRecord != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("process() - write() {}", writeRecord);
                state.compareAndSet(State.INITIAL, State.COMPRESSING);
                write(writeRecord);
                return Action.SCHEDULED;
            }

            boolean hasRemaining = content != null && content.hasRemaining();
            if (LOG.isDebugEnabled())
                LOG.debug("process() - hasRemaining={}", hasRemaining);
            return hasRemaining ? Action.SCHEDULED : Action.SUCCEEDED;
        }

        private void write(WriteRecord writeRecord)
        {
            Callback callback = this;
            if (writeRecord.last)
            {
                state.set(State.FINISHING);
                callback = Callback.combine(Callback.from(this::finished), callback);
            }
            if (writeRecord.callback != null)
                callback = Callback.combine(callback, writeRecord.callback);
            sink.write(writeRecord.last, writeRecord.output, callback);
        }
    }
}
