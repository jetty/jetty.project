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

package org.eclipse.jetty.util.buffer;

import org.eclipse.jetty.util.IO;

public interface WritableBufferPool
{
    WritableBufferPool NON_POOLING = WritableBuffer::allocate;
    Sized SIZED_NON_POOLING = new Sized(IO.DEFAULT_BUFFER_SIZE, false, NON_POOLING);

    WritableBuffer acquire(int size, boolean direct);

    class Sized implements WritableBufferPool
    {
        private final int size;
        private final boolean direct;
        private final WritableBufferPool delegate;

        public Sized(int size, boolean direct, WritableBufferPool delegate)
        {
            this.size = size;
            this.direct = direct;
            this.delegate = delegate;
        }

        public int getSize()
        {
            return size;
        }

        public boolean isDirect()
        {
            return direct;
        }

        public WritableBuffer acquire()
        {
            return delegate.acquire(size, direct);
        }

        public WritableBuffer acquire(int size)
        {
            return delegate.acquire(size, direct);
        }

        public WritableBuffer acquire(boolean direct)
        {
            return delegate.acquire(size, direct);
        }

        @Override
        public WritableBuffer acquire(int size, boolean direct)
        {
            return delegate.acquire(size, direct);
        }
    }
}
