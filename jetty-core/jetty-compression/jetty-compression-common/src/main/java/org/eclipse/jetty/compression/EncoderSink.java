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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EncoderSink implements Content.Sink
{
    private final EncodeBufferCallback encoder = new EncodeBufferCallback();
    private final Content.Sink sink;

    protected EncoderSink(Content.Sink sink)
    {
        this.sink = sink;
    }

    @Override
    public void write(boolean last, RetainableByteBuffer content, Callback callback)
    {
        if (content != null || last)
            encoder.init(last, content, callback);
        else
            callback.succeeded();
    }

    /**
     * Creates a {@link WriteRecord} with the given {@code last} flag and {@code content} buffer.
     * @param last the {@code last} flag to eventually pass to {@link org.eclipse.jetty.io.Content.Sink#write(boolean, RetainableByteBuffer, Callback)}.
     * @param content the buffer to eventually pass to {@link org.eclipse.jetty.io.Content.Sink#write(boolean, RetainableByteBuffer, Callback)}.
     * @return the {@link WriteRecord}.
     * @throws IllegalStateException if {@link #release()} has already been called.
     */
    protected abstract WriteRecord encode(boolean last, RetainableByteBuffer content);

    /**
     * <p>Release all resources held by this instance. Any further {@link #write(boolean, RetainableByteBuffer, Callback) write attempt}
     * fails once this method has been called.</p>
     * <p>Implementation must be idempotent.</p>
     */
    protected void release()
    {
    }

    public record WriteRecord(boolean last, RetainableByteBuffer output) {}

    private class EncodeBufferCallback extends IteratingCallback
    {
        private enum State
        {
            // Initial state, nothing has been attempted yet
            INITIAL,
            // We have started compressing
            COMPRESSING,
            // The last content is being encoded and is being flushed
            FINISHING,
            // The final content has been sent (final state)
            FINISHED
        }

        private static final Logger LOG = LoggerFactory.getLogger(EncodeBufferCallback.class);

        private final AtomicReference<State> state = new AtomicReference<>();
        private RetainableByteBuffer content;
        private boolean last;
        private Callback callback;

        private EncodeBufferCallback()
        {
            super(true);
        }

        private void init(boolean last, RetainableByteBuffer content, Callback callback)
        {
            if (reset())
            {
                this.state.set(State.INITIAL);
                this.last = last;
                this.content = content == null ? RetainableByteBuffer.Mutable.empty() : content;
                this.content.retain();
                this.callback = callback;
                iterate();
            }
            else
            {
                callback.failed(new IllegalStateException("Could not initialize encoder " + EncoderSink.this));
            }
        }

        @Override
        protected Action process()
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
                writeRecord.output().release();
                return Action.SCHEDULED;
            }

            boolean hasRemaining = content.hasRemaining();
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
                callback = Callback.from(this::finished, callback);
            }
            sink.write(writeRecord.last(), writeRecord.output(), callback);
        }

        protected void finished()
        {
            state.set(State.FINISHED);
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            content = Retainable.dispose(content);
            Throwable disposeFailure = dispose();
            super.onCompleted(ExceptionUtil.combine(causeOrNull, disposeFailure));
        }

        private Throwable dispose()
        {
            try
            {
                if (last)
                    release();
                return null;
            }
            catch (Throwable x)
            {
                return x;
            }
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            callback.failed(cause);
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s,last=%b]",
                super.toString(),
                content,
                last
            );
        }
    }
}
