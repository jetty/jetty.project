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

package org.eclipse.jetty.server;

import java.io.IOException;

/**
 * OutputWriter.
 * A writer that can wrap a {@link HttpOutput} stream and provide
 * character encodings.
 *
 * The UTF-8 encoding is done by this class and no additional
 * buffers or Writers are used.
 * The UTF-8 code was inspired by http://javolution.org
 */
public class Utf8HttpWriter extends HttpWriter
{
    int _surrogate = 0;

    public Utf8HttpWriter(HttpOutput out)
    {
        super(out);
    }

    @Override
    public void write(char[] s, int offset, int length) throws IOException
    {
        HttpOutput out = _out;

        while (length > 0)
        {
            _bytes.reset();
            int chars = Math.min(length, MAX_OUTPUT_CHARS);

            byte[] buffer = _bytes.getBuf();
            int bytes = _bytes.getCount();

            if (bytes + chars > buffer.length)
                chars = buffer.length - bytes;

            for (int i = 0; i < chars; i++)
            {
                int code = s[offset + i];

                // Do we already have a surrogate?
                if (_surrogate == 0)
                {
                    // No - is this char code a surrogate?
                    if (Character.isHighSurrogate((char)code))
                    {
                        _surrogate = code; // UCS-?
                        continue;
                    }
                }
                // else handle a low surrogate
                else if (Character.isLowSurrogate((char)code))
                {
                    code = Character.toCodePoint((char)_surrogate, (char)code); // UCS-4
                }
                // else UCS-2
                else
                {
                    code = _surrogate; // UCS-2
                    _surrogate = 0; // USED
                    i--;
                }

                if ((code & 0xffffff80) == 0)
                {
                    // 1b
                    if (bytes >= buffer.length)
                    {
                        chars = i;
                        break;
                    }
                    buffer[bytes++] = (byte)(code);
                }
                else
                {
                    if ((code & 0xfffff800) == 0)
                    {
                        // 2b
                        if (bytes + 2 > buffer.length)
                        {
                            chars = i;
                            break;
                        }
                        buffer[bytes++] = (byte)(0xc0 | (code >> 6));
                        buffer[bytes++] = (byte)(0x80 | (code & 0x3f));
                    }
                    else if ((code & 0xffff0000) == 0)
                    {
                        // 3b
                        if (bytes + 3 > buffer.length)
                        {
                            chars = i;
                            break;
                        }
                        buffer[bytes++] = (byte)(0xe0 | (code >> 12));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 6) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | (code & 0x3f));
                    }
                    else if ((code & 0xff200000) == 0)
                    {
                        // 4b
                        if (bytes + 4 > buffer.length)
                        {
                            chars = i;
                            break;
                        }
                        buffer[bytes++] = (byte)(0xf0 | (code >> 18));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 12) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 6) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | (code & 0x3f));
                    }
                    else if ((code & 0xf4000000) == 0)
                    {
                        // 5b
                        if (bytes + 5 > buffer.length)
                        {
                            chars = i;
                            break;
                        }
                        buffer[bytes++] = (byte)(0xf8 | (code >> 24));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 18) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 12) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 6) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | (code & 0x3f));
                    }
                    else if ((code & 0x80000000) == 0)
                    {
                        // 6b
                        if (bytes + 6 > buffer.length)
                        {
                            chars = i;
                            break;
                        }
                        buffer[bytes++] = (byte)(0xfc | (code >> 30));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 24) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 18) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 12) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | ((code >> 6) & 0x3f));
                        buffer[bytes++] = (byte)(0x80 | (code & 0x3f));
                    }
                    else
                    {
                        buffer[bytes++] = (byte)('?');
                    }

                    _surrogate = 0; // USED

                    if (bytes == buffer.length)
                    {
                        chars = i + 1;
                        break;
                    }
                }
            }
            _bytes.setCount(bytes);

            _bytes.writeTo(out);
            length -= chars;
            offset += chars;
        }
    }
}
