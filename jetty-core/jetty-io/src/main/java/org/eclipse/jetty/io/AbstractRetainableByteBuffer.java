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
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>Abstract implementation of {@link RetainableByteBuffer} with
 * reference counting.</p>
 */
public abstract class AbstractRetainableByteBuffer implements RetainableByteBuffer
{
    private final ReferenceCounter refCount = new ReferenceCounter(0);
    private final ByteBuffer byteBuffer;

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
        return byteBuffer;
    }

    @Override
    public String toString()
    {
        return "%s@%x[rc=%d,%s]".formatted(getClass().getSimpleName(), hashCode(), refCount.get(), BufferUtil.toDetailString(byteBuffer));
    }
}
