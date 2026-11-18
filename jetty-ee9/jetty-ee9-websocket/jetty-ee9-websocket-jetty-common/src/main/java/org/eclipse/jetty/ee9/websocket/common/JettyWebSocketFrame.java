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

package org.eclipse.jetty.ee9.websocket.common;

import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.Frame;

public class JettyWebSocketFrame implements org.eclipse.jetty.ee9.websocket.api.Frame, AutoCloseable
{
    private final Frame frame;

    public JettyWebSocketFrame(Frame frame)
    {
        this.frame = Objects.requireNonNull(frame);
        frame.retain();
    }

    @Override
    public byte[] getMask()
    {
        return frame.getMask();
    }

    @Override
    public byte getOpCode()
    {
        return frame.getOpCode();
    }

    @Override
    public ByteBuffer getPayload()
    {
        try (RetainableByteBuffer buffer = frame.acquire())
        {
            try (RetainableByteBuffer slice = buffer.slice())
            {
                return slice.getByteBuffer(false);
            }
        }
    }

    @Override
    public int getPayloadLength()
    {
        return Math.toIntExact(frame.getPayloadLength());
    }

    @Override
    public Type getType()
    {
        return Type.from(getOpCode());
    }

    @Override
    public boolean hasPayload()
    {
        return frame.hasPayload();
    }

    @Override
    public boolean isFin()
    {
        return frame.isFin();
    }

    @Override
    public boolean isMasked()
    {
        return frame.isMasked();
    }

    @Override
    public boolean isRsv1()
    {
        return frame.isRsv1();
    }

    @Override
    public boolean isRsv2()
    {
        return frame.isRsv2();
    }

    @Override
    public boolean isRsv3()
    {
        return frame.isRsv3();
    }

    @Override
    public void close()
    {
        frame.release();
    }

    @Override
    public String toString()
    {
        return frame.toString();
    }
}
