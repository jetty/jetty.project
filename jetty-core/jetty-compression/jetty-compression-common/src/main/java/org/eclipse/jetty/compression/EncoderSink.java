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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

public abstract class EncoderSink implements Content.Sink
{
    private final Content.Sink sink;
    private final Flusher flusher;

    protected EncoderSink(Content.Sink sink)
    {
        this.sink = sink;
        this.flusher = new Flusher(this.sink);
    }

    /**
     * Offer a write operation to the underlying {@link Content.Sink} delegate.
     *
     * @param last if last write
     * @param byteBuffer the byteBuffer to write
     * @param callback the callback to use on completion of write
     */
    protected void offerWrite(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        this.flusher.offer(last, byteBuffer, callback);
    }

    private static class Flusher extends IteratingCallback
    {
        private static final ByteBuffer COMPLETE_CALLBACK = BufferUtil.allocate(0);

        private final Content.Sink sink;
        private boolean last;
        private ByteBuffer buffer;
        private Callback callback;
        private boolean lastWritten;

        Flusher(Content.Sink sink)
        {
            this.sink = sink;
        }

        void offer(Callback callback)
        {
            offer(false, COMPLETE_CALLBACK, callback);
        }

        void offer(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (this.callback != null)
                throw new WritePendingException();
            this.last = last;
            buffer = byteBuffer;
            this.callback = callback;
            iterate();
        }

        @Override
        protected Action process()
        {
            if (lastWritten)
                return Action.SUCCEEDED;
            if (callback == null)
                return Action.IDLE;
            if (buffer != COMPLETE_CALLBACK)
            {
                lastWritten = last;
                sink.write(last, buffer, this);
            }
            else
            {
                succeeded();
            }
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            buffer = null;
            Callback callback = this.callback;
            this.callback = null;
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            buffer = null;
            callback.failed(cause);
        }
    }
}
