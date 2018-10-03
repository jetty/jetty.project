//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.core.Frame;

import java.nio.ByteBuffer;

public class JettyWebSocketFrame implements org.eclipse.jetty.websocket.api.extensions.Frame
{
    private final Frame frame;

    public JettyWebSocketFrame(Frame frame)
    {
        this.frame = frame;
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
    public boolean isLast()
    {
        return isFin();
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
    public String toString()
    {
        return frame.toString();
    }
}
