//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

public class NullByteBufferPool implements ByteBufferPool
{
    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        if (direct)
            return BufferUtil.allocateDirect(size);
        else
            return BufferUtil.allocate(size);
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        BufferUtil.clear(buffer);
    }
}
