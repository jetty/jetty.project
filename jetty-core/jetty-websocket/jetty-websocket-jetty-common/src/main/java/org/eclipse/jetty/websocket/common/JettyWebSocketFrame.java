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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

public class JettyWebSocketFrame implements org.eclipse.jetty.websocket.api.Frame
{
    private final Frame frame;
    private final byte effectiveOpCode;

    /**
     * @param frame the core websocket {@link Frame} to wrap as a {@link org.eclipse.jetty.websocket.api.Frame}.
     * @deprecated there is no alternative intended to publicly construct a {@link JettyWebSocketFrame}.
     */
    @Deprecated(forRemoval = true, since = "12.1.0")
    public JettyWebSocketFrame(Frame frame)
    {
        this(frame, frame.getOpCode());
    }

    /**
     * @param frame the core websocket {@link Frame} to wrap as a Jetty API {@link org.eclipse.jetty.websocket.api.Frame}.
     * @param effectiveOpCode the effective OpCode of the Frame, where any CONTINUATION should be replaced with the
     * initial opcode of that websocket message.
     */
    JettyWebSocketFrame(Frame frame, byte effectiveOpCode)
    {
        this.frame = frame;
        this.effectiveOpCode = effectiveOpCode;
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
        return frame.getPayload().asReadOnlyBuffer();
    }

    @Override
    public int getPayloadLength()
    {
        return frame.getPayloadLength();
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
    public byte getEffectiveOpCode()
    {
        return effectiveOpCode;
    }

    @Override
    public CloseStatus getCloseStatus()
    {
        if (getOpCode() != OpCode.CLOSE)
            return null;
        org.eclipse.jetty.websocket.core.CloseStatus closeStatus = org.eclipse.jetty.websocket.core.CloseStatus.getCloseStatus(frame);
        return new CloseStatus(closeStatus.getCode(), closeStatus.getReason());
    }

    @Override
    public String toString()
    {
        return frame.toString();
    }
}
