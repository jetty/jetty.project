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

public class DelegateFrame extends Frame
{
    private final Frame delegate;

    public DelegateFrame(Frame delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public boolean isControlFrame()
    {
        return delegate.isControlFrame();
    }

    @Override
    public boolean isDataFrame()
    {
        return delegate.isDataFrame();
    }

    @Override
    public void assertValid()
    {
        delegate.assertValid();
    }

    @Override
    protected void copyHeaders(Frame frame)
    {
        delegate.copyHeaders(frame);
    }

    @Override
    public boolean equals(Object obj)
    {
        return delegate.equals(obj);
    }

    @Override
    public byte[] getMask()
    {
        return delegate.getMask();
    }

    @Override
    public ByteBuffer getPayload()
    {
        return delegate.getPayload();
    }

    @Override
    public String getPayloadAsUTF8()
    {
        return delegate.getPayloadAsUTF8();
    }

    @Override
    public int getPayloadLength()
    {
        return delegate.getPayloadLength();
    }

    @Override
    public Type getType()
    {
        return delegate.getType();
    }

    @Override
    public int hashCode()
    {
        return delegate.hashCode();
    }

    @Override
    public boolean hasPayload()
    {
        return delegate.hasPayload();
    }

    @Override
    public boolean isFin()
    {
        return delegate.isFin();
    }

    @Override
    public boolean isMasked()
    {
        return delegate.isMasked();
    }

    @Override
    public boolean isRsv1()
    {
        return delegate.isRsv1();
    }

    @Override
    public boolean isRsv2()
    {
        return delegate.isRsv2();
    }

    @Override
    public boolean isRsv3()
    {
        return delegate.isRsv3();
    }

    @Override
    public void reset()
    {
        delegate.reset();
    }

    @Override
    public Frame setFin(boolean fin)
    {
        return delegate.setFin(fin);
    }

    @Override
    public Frame setMask(byte[] maskingKey)
    {
        return delegate.setMask(maskingKey);
    }

    @Override
    public Frame setMasked(boolean mask)
    {
        return delegate.setMasked(mask);
    }

    @Override
    protected Frame setOpCode(byte op)
    {
        return delegate.setOpCode(op);
    }

    @Override
    public Frame setPayload(ByteBuffer buf)
    {
        return delegate.setPayload(buf);
    }

    @Override
    public Frame setPayload(String str)
    {
        return delegate.setPayload(str);
    }

    @Override
    public Frame setPayload(byte[] buf)
    {
        return delegate.setPayload(buf);
    }

    @Override
    public Frame setRsv1(boolean rsv1)
    {
        return delegate.setRsv1(rsv1);
    }

    @Override
    public Frame setRsv2(boolean rsv2)
    {
        return delegate.setRsv2(rsv2);
    }

    @Override
    public Frame setRsv3(boolean rsv3)
    {
        return delegate.setRsv3(rsv3);
    }

    @Override
    public byte getOpCode()
    {
        return delegate.getOpCode();
    }
}
