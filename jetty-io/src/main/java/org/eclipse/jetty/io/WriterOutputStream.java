//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Wrap a Writer as an OutputStream.
 * When all you have is a Writer and only an OutputStream will do.
 * Try not to use this as it indicates that your design is a dogs
 * breakfast (JSP made me write it).
 */
public class WriterOutputStream extends OutputStream
{
    protected final Writer _writer;
    protected final Charset _encoding;

    public WriterOutputStream(Writer writer, String encoding)
    {
        _writer = writer;
        _encoding = encoding == null ? null : Charset.forName(encoding);
    }

    public WriterOutputStream(Writer writer)
    {
        _writer = writer;
        _encoding = null;
    }

    @Override
    public void close()
        throws IOException
    {
        _writer.close();
    }

    @Override
    public void flush()
        throws IOException
    {
        _writer.flush();
    }

    @Override
    public void write(byte[] b)
        throws IOException
    {
        if (_encoding == null)
            _writer.write(new String(b));
        else
            _writer.write(new String(b, _encoding));
    }

    @Override
    public void write(byte[] b, int off, int len)
        throws IOException
    {
        if (_encoding == null)
            _writer.write(new String(b, off, len));
        else
            _writer.write(new String(b, off, len, _encoding));
    }

    @Override
    public void write(int b)
        throws IOException
    {
        write(new byte[]{(byte)b});
    }
}
