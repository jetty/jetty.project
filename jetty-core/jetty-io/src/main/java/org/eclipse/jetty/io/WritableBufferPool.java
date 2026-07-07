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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public interface WritableBufferPool extends org.eclipse.jetty.util.buffer.WritableBufferPool
{
    WritableBufferPool NON_POOLING = WritableBuffer::allocate;

    static WritableBufferPool wrap(ByteBufferPool byteBufferPool)
    {
        return (size, direct) ->
        {
            RetainableByteBuffer.Mutable rbbm = byteBufferPool.acquire(size, direct);
            BufferUtil.flipToFill(rbbm.getByteBuffer());
            return WritableBuffer.wrap(rbbm.getByteBuffer(), rbbm);
        };
    }

    static Sized wrap(ByteBufferPool.Sized sizedByteBufferPool)
    {
        return new Sized(sizedByteBufferPool.getSize(), sizedByteBufferPool.isDirect(), WritableBufferPool.wrap(sizedByteBufferPool.getWrapped()));
    }
}
