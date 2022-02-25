//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.frames;

public class WindowUpdateFrame extends Frame
{
    public static final int WINDOW_UPDATE_LENGTH = 4;

    private final int streamId;
    private final int windowDelta;

    public WindowUpdateFrame(int streamId, int windowDelta)
    {
        super(FrameType.WINDOW_UPDATE);
        this.streamId = streamId;
        this.windowDelta = windowDelta;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getWindowDelta()
    {
        return windowDelta;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d,delta=%d", super.toString(), streamId, windowDelta);
    }
}
