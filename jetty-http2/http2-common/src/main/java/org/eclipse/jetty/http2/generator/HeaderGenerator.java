//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;

public class HeaderGenerator
{
    public ByteBuffer generate(ByteBufferPool.Lease lease, FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        ByteBuffer header = lease.acquire(capacity, true);
        header.putShort((short)length);
        header.put((byte)frameType.getType());
        header.put((byte)flags);
        header.putInt(streamId);
        return header;
    }
}
