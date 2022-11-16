//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested.resource;

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

    public ByteBufferRangeWriter(ByteBuffer buffer)
    {
        this.buffer = buffer;
    }

    @Override
    public void close() throws IOException
    {
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
