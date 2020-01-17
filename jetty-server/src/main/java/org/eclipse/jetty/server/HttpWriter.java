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
import java.io.Writer;

import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.Callback;

/**
 *
 */
public abstract class HttpWriter extends Writer
{
    public static final int MAX_OUTPUT_CHARS = 512; // TODO should this be configurable? super size is 1024

    final HttpOutput _out;
    final ByteArrayOutputStream2 _bytes;
    final char[] _chars;

    public HttpWriter(HttpOutput out)
    {
        _out = out;
        _chars = new char[MAX_OUTPUT_CHARS];
        _bytes = new ByteArrayOutputStream2(MAX_OUTPUT_CHARS);  // TODO should this be pooled - or do we just recycle the writer?
    }

    @Override
    public void close() throws IOException
    {
        _out.close();
    }

    public void complete(Callback callback)
    {
        _out.complete(callback);
    }

    @Override
    public void flush() throws IOException
    {
        _out.flush();
    }

    @Override
    public void write(String s, int offset, int length) throws IOException
    {
        while (length > MAX_OUTPUT_CHARS)
        {
            write(s, offset, MAX_OUTPUT_CHARS);
            offset += MAX_OUTPUT_CHARS;
            length -= MAX_OUTPUT_CHARS;
        }

        s.getChars(offset, offset + length, _chars, 0);
        write(_chars, 0, length);
    }

    @Override
    public void write(char[] s, int offset, int length) throws IOException
    {
        throw new AbstractMethodError();
    }
}
