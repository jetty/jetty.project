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

package org.eclipse.jetty.util.buffer.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// A [ByteBufferPool] that does not pool its [RetainableByteBuffer]s.
///
/// @see RetainableByteBuffer#wrap(ByteBuffer)
public class NonPoolingByteBufferPool implements ByteBufferPool
{
    @Override
    public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
    {
        ByteBuffer byteBuffer = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
        return RetainableByteBuffer.Mutable.wrap(byteBuffer);
    }
}
