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

package org.eclipse.jetty.websocket.core.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.core.CloseStatus;

/**
 * Immutable, Read-only, Frame implementation.
 */
public class ReadOnlyFrame extends Frame
{

    public ReadOnlyFrame(Frame frame)
    {
        super(frame.getOpCode());

        copyHeaders(frame);
        ByteBuffer payload = frame.getPayload();
        if (payload != null)
        {
            ByteBuffer payloadCopy = ByteBuffer.allocate(payload.remaining());
            payloadCopy.put(payload.slice()).flip();
            frame.setPayload(payloadCopy);
        }
    }

    //TODO should these throw exceptions

    @Override
    protected void copyHeaders(Frame frame)
    {}

    @Override
    public void reset()
    {}

    @Override
    public Frame setFin(boolean fin)
    {
        return this;
    }

    @Override
    public Frame setMask(byte[] maskingKey)
    {
        return this;
    }

    @Override
    public Frame setMasked(boolean mask)
    {
        return this;
    }

    @Override
    protected Frame setOpCode(byte op)
    {
        return this;
    }

    @Override
    public Frame setPayload(ByteBuffer buf)
    {
        return this;
    }

    @Override
    public Frame setPayload(String str)
    {
        return this;
    }

    @Override
    public Frame setPayload(CloseStatus closeStatus)
    {
        return this;
    }

    @Override
    public Frame setRsv1(boolean rsv1)
    {
        return this;
    }

    @Override
    public Frame setRsv2(boolean rsv2)
    {
        return this;
    }

    @Override
    public Frame setRsv3(boolean rsv3)
    {
        return this;
    }
}
