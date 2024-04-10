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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>Abstract implementation of {@link RetainableByteBuffer} with
 * reference counting.</p>
 */
public abstract class AbstractRetainableByteBuffer implements RetainableByteBuffer.Appendable
{
    private final ReferenceCounter refCount = new ReferenceCounter(0);
    private final ByteBuffer byteBuffer;
    private int flipPos = -1;

    public AbstractRetainableByteBuffer(ByteBuffer byteBuffer)
    {
        this.byteBuffer = Objects.requireNonNull(byteBuffer);
    }

    /**
     * @see ReferenceCounter#acquire()
     */
    protected void acquire()
    {
        refCount.acquire();
    }

    @Override
    public int remaining()
    {
        if (flipPos < 0)
            return byteBuffer.remaining();
        return byteBuffer.position() - flipPos;
    }

    @Override
    public boolean hasRemaining()
    {
        if (flipPos < 0)
            return byteBuffer.hasRemaining();

        return flipPos > 0 || byteBuffer.position() > 0;
    }

    @Override
    public boolean canRetain()
    {
        return refCount.canRetain();
    }

    @Override
    public void retain()
    {
        refCount.retain();
    }

    @Override
    public boolean release()
    {
        return refCount.release();
    }

    @Override
    public boolean isRetained()
    {
        return refCount.isRetained();
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        if (flipPos >= 0)
        {
            BufferUtil.flipToFlush(byteBuffer, flipPos);
            flipPos = -1;
        }
        return byteBuffer;
    }

    @Override
    public boolean append(ByteBuffer bytes) throws ReadOnlyBufferException
    {
        if (isRetained())
            throw new ReadOnlyBufferException();
        if (flipPos < 0)
            flipPos = BufferUtil.flipToFill(byteBuffer);
        BufferUtil.put(bytes, byteBuffer);
        return !bytes.hasRemaining();
    }

    @Override
    public String toDetailString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(getClass().getSimpleName());
        buf.append("@");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append("[");
        buf.append(remaining());
        buf.append("/");
        buf.append(capacity());
        buf.append(",");
        buf.append(refCount);
        buf.append("]");
        if (refCount.canRetain())
        {
            buf.append("={");
            RetainableByteBuffer.appendDebugString(buf, this);
            buf.append("}");
        }
        return buf.toString();
    }

    @Override
    public String toString()
    {
        return toDetailString();
    }
}
