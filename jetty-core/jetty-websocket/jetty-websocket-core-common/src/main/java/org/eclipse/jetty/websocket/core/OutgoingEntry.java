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

public class OutgoingEntry
{
    private final Frame frame;
    private final Callback callback;
    private final boolean batch;

    public OutgoingEntry(Frame frame, Callback callback, boolean batch)
    {
        this.frame = frame;
        this.callback = callback;
        this.batch = batch;
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

    @Override
    public String toString()
    {
        return frame.toString();
    }

    public static class Builder
    {
        private Frame _frame;
        private Callback _callback;
        private boolean _batch;

        public Builder(OutgoingEntry outgoingEntry)
        {
            _frame = outgoingEntry.getFrame();
            _callback = outgoingEntry.getCallback();
            _batch = outgoingEntry.isBatch();
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

        public OutgoingEntry build()
        {
            return new OutgoingEntry(_frame, _callback, _batch);
        }
    }
}
