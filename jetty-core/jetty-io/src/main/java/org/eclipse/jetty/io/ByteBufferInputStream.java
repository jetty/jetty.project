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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Present a ByteBuffer as an InputStream.
 */
public class ByteBufferInputStream extends InputStream
{
    final ByteBuffer buf;

    public ByteBufferInputStream(ByteBuffer buf)
    {
        this.buf = buf;
    }

    @Override
    public int available() throws IOException
    {
        return buf.remaining();
    }

    public int read() throws IOException
    {
        if (!buf.hasRemaining())
        {
            return -1;
        }
        return buf.get() & 0xFF;
    }

    public int read(byte[] bytes, int off, int len) throws IOException
    {
        if (!buf.hasRemaining())
        {
            return -1;
        }

        len = Math.min(len, buf.remaining());
        buf.get(bytes, off, len);
        return len;
    }
}
