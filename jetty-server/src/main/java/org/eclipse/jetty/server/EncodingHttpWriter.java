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
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

/**
 *
 */
public class EncodingHttpWriter extends HttpWriter
{
    final Writer _converter;

    public EncodingHttpWriter(HttpOutput out, String encoding)
    {
        super(out);
        try
        {
            _converter = new OutputStreamWriter(_bytes, encoding);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(char[] s, int offset, int length) throws IOException
    {
        HttpOutput out = _out;

        while (length > 0)
        {
            _bytes.reset();
            int chars = Math.min(length, MAX_OUTPUT_CHARS);

            _converter.write(s, offset, chars);
            _converter.flush();
            _bytes.writeTo(out);
            length -= chars;
            offset += chars;
        }
    }
}
