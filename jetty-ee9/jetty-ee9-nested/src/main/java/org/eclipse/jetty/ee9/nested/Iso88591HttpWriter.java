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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;

/**
 *
 */
public class Iso88591HttpWriter extends HttpWriter
{

    public Iso88591HttpWriter(HttpOutput out)
    {
        super(out);
    }

    @Override
    public void write(char[] s, int offset, int length) throws IOException
    {
        HttpOutput out = _out;

        if (length == 1)
        {
            int c = s[offset];
            out.write(c < 256 ? c : '?');
            return;
        }

        while (length > 0)
        {
            _bytes.reset();
            int chars = Math.min(length, MAX_OUTPUT_CHARS);

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
