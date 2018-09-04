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

/**
 * Immutable, Read-only, Frame implementation.
 */
public class ReadOnlyFrame extends Frame
{
    public ReadOnlyFrame(Frame frame)
    {
        super(frame.finRsvOp,frame.isMasked()?frame.getMask():null,frame.getPayload());
    }

    @Override
    public ByteBuffer getPayload()
    {
        ByteBuffer buffer = super.getPayload();
        if(buffer == null)
            return null;
        
        return buffer.asReadOnlyBuffer();
    }

    @Override
    protected void copyHeaders(Frame frame)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reset()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setFin(boolean fin)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setMask(byte[] maskingKey)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setMasked(boolean mask)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    protected Frame setOpCode(byte op)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setPayload(ByteBuffer buf)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setPayload(String str)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setPayload(byte[] buf)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setRsv1(boolean rsv1)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setRsv2(boolean rsv2)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame setRsv3(boolean rsv3)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame asReadOnly()
    {
        return this;
    }
}
