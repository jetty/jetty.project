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

package org.eclipse.jetty.server.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

/**
 * ByteBuffer based RangeWriter
 */
public class ByteBufferRangeWriter implements RangeWriter
{
    private final ByteBuffer buffer;
    private boolean closed = false;

    public ByteBufferRangeWriter(ByteBuffer buffer)
    {
        this.buffer = buffer.asReadOnlyBuffer();
    }

    @Override
    public void close() throws IOException
    {
        closed = true;
    }

    @Override
    public void writeTo(OutputStream outputStream, long skipTo, long length) throws IOException
    {
        if (skipTo > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Unsupported skipTo " + skipTo + " > " + Integer.MAX_VALUE);
        }

        if (length > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Unsupported length " + skipTo + " > " + Integer.MAX_VALUE);
        }

        ByteBuffer src = buffer.slice();
        src.position((int)skipTo);
        src.limit(Math.addExact((int)skipTo, (int)length));
        BufferUtil.writeTo(src, outputStream);
    }
}
