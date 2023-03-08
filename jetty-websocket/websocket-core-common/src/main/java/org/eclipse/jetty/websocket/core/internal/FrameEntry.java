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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;

public class FrameEntry
{
    public final Frame frame;
    public final Callback callback;
    public final boolean batch;

    public FrameEntry(Frame frame, Callback callback, boolean batch)
    {
        this.frame = frame;
        this.callback = callback;
        this.batch = batch;
    }

    @Override
    public String toString()
    {
        return frame.toString();
    }
}
