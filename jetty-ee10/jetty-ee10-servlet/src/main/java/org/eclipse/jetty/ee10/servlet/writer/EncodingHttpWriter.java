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

package org.eclipse.jetty.ee10.servlet.writer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

import org.eclipse.jetty.ee10.servlet.HttpOutput;

/**
 *
 */
public class EncodingHttpWriter extends HttpWriter
{
    final Writer _converter;

    public EncodingHttpWriter(HttpOutput out, String encoding) throws IOException
    {
        super(out);
        _converter = new OutputStreamWriter(_bytes, encoding);
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
