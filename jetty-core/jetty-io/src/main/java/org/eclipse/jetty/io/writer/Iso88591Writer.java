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

package org.eclipse.jetty.io.writer;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An implementation of {@link AbstractOutputStreamWriter} for
 * optimal ISO-8859-1 conversion.
 * The ISO-8859-1 encoding is done by this class and no additional
 * buffers or Writers are used.
 */
public class Iso88591Writer extends AbstractOutputStreamWriter
{
    public Iso88591Writer(OutputStream out)
    {
        super(out);
    }

    @Override
    public void write(char[] s, int offset, int length) throws IOException
    {
        OutputStream out = _out;

        if (length == 1)
        {
            int c = s[offset];
            out.write(c < 256 ? c : '?');
            return;
        }

        while (length > 0)
        {
            _bytes.reset();
            int chars = Math.min(length, _maxWriteSize);

            byte[] buffer = _bytes.getBuf();
            int bytes = _bytes.getCount();

            if (chars > buffer.length - bytes)
                chars = buffer.length - bytes;

            for (int i = 0; i < chars; i++)
            {
                int c = s[offset + i];
                buffer[bytes++] = (byte)(c < 256 ? c : '?');
            }
            if (bytes >= 0)
                _bytes.setCount(bytes);

            _bytes.writeTo(out);
            length -= chars;
            offset += chars;
        }
    }
}
