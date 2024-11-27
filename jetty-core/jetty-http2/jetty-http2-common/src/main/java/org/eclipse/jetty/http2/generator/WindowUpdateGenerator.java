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

package org.eclipse.jetty.http2.generator;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.io.RetainableByteBuffer;

public class WindowUpdateGenerator extends FrameGenerator
{
    public WindowUpdateGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Frame frame)
    {
        WindowUpdateFrame windowUpdateFrame = (WindowUpdateFrame)frame;
        return generateWindowUpdate(accumulator, windowUpdateFrame.getStreamId(), windowUpdateFrame.getWindowDelta());
    }

    public int generateWindowUpdate(RetainableByteBuffer.Mutable accumulator, int streamId, int windowUpdate)
    {
        if (windowUpdate < 0)
            throw new IllegalArgumentException("Invalid window update: " + windowUpdate);

        generateHeader(accumulator, FrameType.WINDOW_UPDATE, WindowUpdateFrame.WINDOW_UPDATE_LENGTH, Flags.NONE, streamId);
        accumulator.putInt(windowUpdate);

        return Frame.HEADER_LENGTH + WindowUpdateFrame.WINDOW_UPDATE_LENGTH;
    }
}
