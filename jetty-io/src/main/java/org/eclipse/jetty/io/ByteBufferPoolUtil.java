//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;

public class ByteBufferPoolUtil
{
    public static ByteBuffer resourceToBuffer(Resource resource, boolean direct, ByteBufferPool bufferPool) throws IOException
    {
        long len = resource.length();
        if (len < 0)
            throw new IllegalArgumentException("invalid resource: " + resource + " len=" + len);

        if (len > Integer.MAX_VALUE)
        {
            // This method cannot handle resources of this size.
            return null;
        }

        int ilen = (int)len;
        ByteBuffer buffer;
        if (bufferPool != null)
            buffer = bufferPool.acquire(ilen, direct);
        else
            buffer = direct ? BufferUtil.allocateDirect(ilen) : BufferUtil.allocate(ilen);

        int pos = BufferUtil.flipToFill(buffer);
        if (resource.getFile() != null)
            BufferUtil.readFrom(resource.getFile(), buffer);
        else
        {
            try (InputStream is = resource.getInputStream())
            {
                BufferUtil.readFrom(is, ilen, buffer);
            }
        }
        BufferUtil.flipToFlush(buffer, pos);

        return buffer;
    }
}
