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
