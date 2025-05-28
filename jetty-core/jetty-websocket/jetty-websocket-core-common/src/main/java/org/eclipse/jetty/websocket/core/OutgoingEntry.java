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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;

public class OutgoingEntry
{
    private final Frame frame;
    private final Callback callback;
    private final boolean batch;
    private final long frameTimeout;
    private final long messageTimeout;

    public OutgoingEntry(Frame frame, Callback callback, boolean batch)
    {
        this.frame = frame;
        this.callback = callback;
        this.batch = batch;
        this.frameTimeout = -1;
        this.messageTimeout = -1;
    }

    public OutgoingEntry(Frame frame, Callback callback, boolean batch, long frameTimeout, long messageTimeout)
    {
        this.frame = frame;
        this.callback = callback;
        this.batch = batch;
        this.frameTimeout = frameTimeout;
        this.messageTimeout = messageTimeout;
    }

    public Frame getFrame()
    {
        return frame;
    }

    public Callback getCallback()
    {
        return callback;
    }

    public boolean isBatch()
    {
        return batch;
    }

    public long getFrameTimeout()
    {
        return frameTimeout;
    }

    public long getMessageTimeout()
    {
        return messageTimeout;
    }

    @Override
    public String toString()
    {
        return String.format("%s{messageTimeout=%s, frameTimeout=%s, frame=%s, callback=%s, batch=%s}",
            TypeUtil.toShortName(getClass()), messageTimeout, frameTimeout, frame, callback, batch);

    }

    public static class Builder
    {
        private Frame _frame;
        private Callback _callback;
        private boolean _batch;
        private long _frameTimeout;
        private long _messageTimeout;

        public Builder(OutgoingEntry outgoingEntry)
        {
            _frame = outgoingEntry.getFrame();
            _callback = outgoingEntry.getCallback();
            _batch = outgoingEntry.isBatch();
            _frameTimeout = outgoingEntry.getFrameTimeout();
            _messageTimeout = outgoingEntry.getMessageTimeout();
        }

        public Builder frame(Frame frame)
        {
            _frame = frame;
            return this;
        }

        public Builder callback(Callback callback)
        {
            _callback = callback;
            return this;
        }

        public Builder batch(boolean batch)
        {
            _batch = batch;
            return this;
        }

        public Builder frameTimeout(long frameTimeout)
        {
            _frameTimeout = frameTimeout;
            return this;
        }

        public Builder messageTimeout(long messageTimeout)
        {
            _messageTimeout = messageTimeout;
            return this;
        }

        public OutgoingEntry build()
        {
            return new OutgoingEntry(_frame, _callback, _batch, _frameTimeout, _messageTimeout);
        }
    }
}
