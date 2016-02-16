//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * Immutable, Read-only, Frame implementation.
 */
public class ReadOnlyDelegatedFrame implements Frame
{
    private final Frame delegate;
    
    public ReadOnlyDelegatedFrame(Frame frame)
    {
        this.delegate = frame;
    }

    @Override
    public byte[] getMask()
    {
        return delegate.getMask();
    }

    @Override
    public byte getOpCode()
    {
        return delegate.getOpCode();
    }

    @Override
    public ByteBuffer getPayload()
    {
        if(!delegate.hasPayload()) {
            return null;
        }
        return delegate.getPayload().asReadOnlyBuffer();
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
    @Deprecated
    public boolean isLast()
    {
        return delegate.isLast();
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
}
