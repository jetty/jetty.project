//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy.frames;

public class WindowUpdateFrame extends ControlFrame
{
    private final int streamId;
    private final int windowDelta;

    public WindowUpdateFrame(short version, int streamId, int windowDelta)
    {
        super(version, ControlFrameType.WINDOW_UPDATE, (byte)0);
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
        return String.format("%s stream=%d delta=%d", super.toString(), getStreamId(), getWindowDelta());
    }
}
