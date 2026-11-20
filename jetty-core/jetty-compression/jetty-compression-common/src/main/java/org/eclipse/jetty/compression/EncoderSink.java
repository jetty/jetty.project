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
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EncoderSink implements Content.Sink
{
    private final Content.Sink sink;
    private final EncodeBufferCallback encodeBufferCallback = new EncodeBufferCallback();

    protected EncoderSink(Content.Sink sink)
    {
        this.sink = sink;
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        if (content != null || last)
        {
            if (encodeBufferCallback.reset(last, content, callback))
                encodeBufferCallback.iterate();
            else if (encodeBufferCallback.isClosed())
                callback.failed(new IllegalStateException("Already released"));
            else
                callback.failed(new WritePendingException());
        }
        else
            callback.succeeded();
    }

    /**
     * <p>Aborts this sink, typically because the response it writes to has failed.</p>
     * <p>If a write is in progress, its callback is failed once it completes; otherwise,
     * this sink is {@link #release() released} immediately.
     * Any further {@link #write(boolean, ByteBuffer, Callback) write attempt} fails.</p>
     * <p>This method has no effect if this sink has already been released.</p>
     * <p>This method must not be called concurrently with {@link #write(boolean, ByteBuffer, Callback)}.</p>
     *
     * @param failure the cause of the abort
     */
    public void abort(Throwable failure)
    {
        // If a write is in progress, abort() returns true, and onCompleteFailure() releases once it is complete.
        // If no write was ever attempted, abort() returns true, and onCompleteFailure() releases immediately.
        // Otherwise the callback is either between writes, or closed, so it must be released here.
        if (!encodeBufferCallback.abort(failure))
            encodeBufferCallback.closeAndRelease();
    }

    /**
     * Creates a {@link WriteRecord} with the given {@code last} flag and {@code content} buffer.
     * @param last the {@code last} flag to eventually pass to {@link org.eclipse.jetty.io.Content.Sink#write(boolean, ByteBuffer, Callback)}.
     * @param content the buffer to eventually pass to {@link org.eclipse.jetty.io.Content.Sink#write(boolean, ByteBuffer, Callback)}.
     * @return the {@link WriteRecord}.
     */
    protected abstract WriteRecord encode(boolean last, ByteBuffer content);

    /**
     * <p>Release all resources held by this instance.</p>
     * <p>This method is called either after the last write succeeded, after any write failed,
     * or when this sink is {@link #abort(Throwable) aborted}.
     * Any further {@link #write(boolean, ByteBuffer, Callback) write attempt} fails without calling
     * {@link #encode(boolean, ByteBuffer)} once this method has been called.</p>
     * <p>Implementation must be idempotent.</p>
     */
    protected void release()
    {
    }

    public record WriteRecord(boolean last, ByteBuffer output, Callback callback) {}

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
        private final AtomicReference<State> state = new AtomicReference<>(State.INITIAL);
        private final AtomicBoolean released = new AtomicBoolean();
        private ByteBuffer content;
        private boolean last;
        private Callback callback;

        private EncodeBufferCallback()
        {
            super(true);
        }

        private boolean reset(boolean last, ByteBuffer content, Callback callback)
        {
            if (!reset())
                return false;
            this.content = content == null ? BufferUtil.EMPTY_BUFFER : content;
            this.last = last;
            this.callback = callback;
            return true;
        }

        @Override
        public InvocationType getInvocationType()
        {
            Callback callback = this.callback;
            return callback == null ? InvocationType.NON_BLOCKING : callback.getInvocationType();
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
                callback = Callback.from(this::finished, callback);
            }
            if (writeRecord.callback != null)
                callback = Callback.combine(callback, writeRecord.callback);
            sink.write(writeRecord.last, writeRecord.output, callback);
        }

        protected void finished()
        {
            state.set(State.FINISHED);
        }

        private Callback takeCallbackAndReset()
        {
            Callback callback = this.callback;
            this.callback = null;
            this.content = null;
            return callback;
        }

        private void closeAndRelease()
        {
            // Closing forbids any further reset(), so no write
            // can encode() or release() once released.
            close();
            if (released.compareAndSet(false, true))
                release();
        }

        @Override
        protected void onCompleteSuccess()
        {
            Callback callback = takeCallbackAndReset();
            if (last)
                closeAndRelease();
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            // No last write may follow a failed write (e.g. when the client closed the connection),
            // so release now, once the scheduled write has completed and before notifying the
            // callback so that no further write can race with the release.
            Callback callback = takeCallbackAndReset();
            closeAndRelease();
            // The callback is null if abort() was called before any write.
            if (callback != null)
                callback.failed(cause);
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s,last=%b]",
                super.toString(),
                BufferUtil.toDetailString(content),
                last
            );
        }
    }
}
