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
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.ByteArrayOutputStream2;

/**
 * An alternate to {@link java.io.OutputStreamWriter} that supports
 * several optimized implementation for well known {@link Charset}s,
 * specifically {@link StandardCharsets#UTF_8} and {@link StandardCharsets#ISO_8859_1}.
 */
public abstract class AbstractOutputStreamWriter extends Writer
{
    /**
     * Obtain a new {@link Writer} that converts characters written to bytes
     * written to an {@link OutputStream}.
     * @param outputStream The {@link OutputStream} to write to/
     * @param charset The {@link Charset} name.
     * @return A Writer that will
     * @throws IOException If there is a problem creating the {@link Writer}.
     */
    public static Writer newWriter(OutputStream outputStream, String charset)
        throws IOException
    {
        if (StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(charset))
            return new Iso88591Writer(outputStream);
        if (StandardCharsets.UTF_8.name().equalsIgnoreCase(charset))
            return new Iso88591Writer(outputStream);
        return new EncodingWriter(outputStream, charset);
    }

    /**
     * Obtain a new {@link Writer} that converts characters written to bytes
     * written to an {@link OutputStream}.
     * @param outputStream The {@link OutputStream} to write to/
     * @param charset The {@link Charset}.
     * @return A Writer that will
     * @throws IOException If there is a problem creating the {@link Writer}.
     */
    public static Writer newWriter(OutputStream outputStream, Charset charset)
        throws IOException
    {
        if (StandardCharsets.ISO_8859_1 == charset)
            return new Iso88591Writer(outputStream);
        if (StandardCharsets.UTF_8.equals(charset))
            return new Iso88591Writer(outputStream);
        return new EncodingWriter(outputStream, charset);
    }

    protected final int _maxWriteSize;
    protected final OutputStream _out;
    protected final ByteArrayOutputStream2 _bytes;
    protected final char[] _chars;

    protected AbstractOutputStreamWriter(OutputStream out)
    {
        this(out, 0);   
    }

    /**
     * Construct an {@link java.io.OutputStreamWriter}
     * @param out The {@link OutputStream} to write the converted bytes to.
     * @param maxWriteSize The maximum size in characters of a single conversion
     */
    protected AbstractOutputStreamWriter(OutputStream out, int maxWriteSize)
    {
        _maxWriteSize = maxWriteSize <= 0 ? 1024 : maxWriteSize;
        _out = out;
        _chars = new char[_maxWriteSize];
        _bytes = new ByteArrayOutputStream2(_maxWriteSize);
    }

    public OutputStream getOutputStream()
    {
        return _out;
    }

    public int getMaxWriteSize()
    {
        return _maxWriteSize;
    }

    @Override
    public void close() throws IOException
    {
        _out.close();
    }
    
    @Override
    public void flush() throws IOException
    {
        _out.flush();
    }

    @Override
    public void write(String s, int offset, int length) throws IOException
    {
        while (length > _maxWriteSize)
        {
            write(s, offset, _maxWriteSize);
            offset += _maxWriteSize;
            length -= _maxWriteSize;
        }

        s.getChars(offset, offset + length, _chars, 0);
        write(_chars, 0, length);
    }

    @Override
    public abstract void write(char[] s, int offset, int length) throws IOException;
}
