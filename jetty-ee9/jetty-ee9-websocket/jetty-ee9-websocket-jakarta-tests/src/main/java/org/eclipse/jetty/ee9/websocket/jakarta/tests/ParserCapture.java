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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.Parser;

public class ParserCapture
{
    private final Parser parser;
    public BlockingQueue<Frame> framesQueue = new LinkedBlockingDeque<>();
    public boolean closed = false;

    public ParserCapture(Parser parser)
    {
        this.parser = parser;
    }

    public void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            Frame frame = parser.parse(buffer);
            if (frame == null)
                break;
            if (!onFrame(frame))
                break;
        }
    }

    public boolean onFrame(Frame frame)
    {
        framesQueue.offer(Frame.copy(frame));
        if (frame.getOpCode() == OpCode.CLOSE)
            closed = true;
        return true; // it is consumed
    }
}
