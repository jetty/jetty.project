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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * An implementation of {@link AbstractOutputStreamWriter} that internally
 * uses {@link java.io.OutputStreamWriter}.
 */
public class EncodingWriter extends AbstractOutputStreamWriter
{
    final Writer _converter;

    public EncodingWriter(OutputStream out, String encoding) throws IOException
    {
        super(out);
        _converter = new OutputStreamWriter(_bytes, encoding);
    }

    public EncodingWriter(OutputStream out, Charset charset) throws IOException
    {
        super(out);
        _converter = new OutputStreamWriter(_bytes, charset);
    }

    @Override
    public void write(char[] s, int offset, int length) throws IOException
    {
        while (length > 0)
        {
            _bytes.reset();
            int chars = Math.min(length, _maxWriteSize);

            _converter.write(s, offset, chars);
            _converter.flush();
            _bytes.writeTo(_out);
            length -= chars;
            offset += chars;
        }
    }
}
