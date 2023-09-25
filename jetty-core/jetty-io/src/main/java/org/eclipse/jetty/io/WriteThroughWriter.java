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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>An alternate to {@link java.io.OutputStreamWriter} that supports
 * several optimized implementation for well known {@link Charset}s,
 * specifically {@link StandardCharsets#UTF_8} and {@link StandardCharsets#ISO_8859_1}.</p>
 * <p>The implementations of this class will never buffer characters or bytes beyond a call to the
 * {@link #write(char[], int, int)} method, thus written characters will always be available
 * in converted form to the passed {@link OutputStream}</p>.
 */
public abstract class WriteThroughWriter extends Writer
{
    static final int DEFAULT_MAX_WRITE_SIZE = 1024;
    private final int _maxWriteSize;
    protected final OutputStream _out;
    protected final ByteArrayOutputStream2 _bytes;

    protected WriteThroughWriter(OutputStream out)
    {
        this(out, 0);
    }

    /**
     * Construct an {@link java.io.OutputStreamWriter}
     * @param out The {@link OutputStream} to write the converted bytes to.
     * @param maxWriteSize The maximum size in characters of a single conversion
     */
    protected WriteThroughWriter(OutputStream out, int maxWriteSize)
    {
        _maxWriteSize = maxWriteSize <= 0 ? DEFAULT_MAX_WRITE_SIZE : maxWriteSize;
        _out = out;
        _bytes = new ByteArrayOutputStream2(_maxWriteSize);
    }

    /**
     * Obtain a new {@link Writer} that converts characters written to bytes
     * written to an {@link OutputStream}.
     * @param outputStream The {@link OutputStream} to write to/
     * @param charset The {@link Charset} name.
     * @return A Writer that will
     * @throws IOException If there is a problem creating the {@link Writer}.
     */
    public static WriteThroughWriter newWriter(OutputStream outputStream, String charset)
        throws IOException
    {
        if (StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(charset))
            return new Iso88591Writer(outputStream);
        if (StandardCharsets.UTF_8.name().equalsIgnoreCase(charset))
            return new Utf8Writer(outputStream);
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
    public static WriteThroughWriter newWriter(OutputStream outputStream, Charset charset)
        throws IOException
    {
        if (StandardCharsets.ISO_8859_1 == charset)
            return new Iso88591Writer(outputStream);
        if (StandardCharsets.UTF_8.equals(charset))
            return new Utf8Writer(outputStream);
        return new EncodingWriter(outputStream, charset);
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
    public abstract WriteThroughWriter append(CharSequence sequence) throws IOException;

    @Override
    public void write(String string, int offset, int length) throws IOException
    {
        while (length > _maxWriteSize)
        {
            append(StringUtil.subSequence(string, offset, _maxWriteSize));
            offset += _maxWriteSize;
            length -= _maxWriteSize;
        }

        append(StringUtil.subSequence(string, offset, length));
    }

    @Override
    public void write(char[] chars, int offset, int length) throws IOException
    {
        while (length > _maxWriteSize)
        {
            append(StringUtil.subSequence(chars, offset, _maxWriteSize));
            offset += _maxWriteSize;
            length -= _maxWriteSize;
        }

        append(StringUtil.subSequence(chars, offset, length));
    }

    /**
     * An implementation of {@link WriteThroughWriter} for
     * optimal ISO-8859-1 conversion.
     * The ISO-8859-1 encoding is done by this class and no additional
     * buffers or Writers are used.
     */
    private static class Iso88591Writer extends WriteThroughWriter
    {
        private Iso88591Writer(OutputStream out)
        {
            super(out);
        }

        @Override
        public WriteThroughWriter append(CharSequence charSequence) throws IOException
        {
            assert charSequence.length() <= getMaxWriteSize();

            if (charSequence.length() == 1)
            {
                int c = charSequence.charAt(0);
                _out.write(c < 256 ? c : '?');
                return this;
            }

            _bytes.reset();
            int bytes = 0;
            byte[] buffer = _bytes.getBuf();
            int length = charSequence.length();
            for (int offset = 0; offset < length; offset++)
            {
                int c = charSequence.charAt(offset);
                buffer[bytes++] = (byte)(c < 256 ? c : '?');
            }
            if (bytes >= 0)
                _bytes.setCount(bytes);
            _bytes.writeTo(_out);
            return this;
        }
    }

    /**
     * An implementation of {@link WriteThroughWriter} for
     * an optimal UTF-8 conversion.
     * The UTF-8 encoding is done by this class and no additional
     * buffers or Writers are used.
     * The UTF-8 code was inspired by <a href="http://javolution.org">...</a>
     */
    private static class Utf8Writer extends WriteThroughWriter
    {
        int _surrogate = 0;

        private Utf8Writer(OutputStream out)
        {
            super(out);
        }

        @Override
        public WriteThroughWriter append(CharSequence charSequence) throws IOException
        {
            assert charSequence.length() <= getMaxWriteSize();
            int length = charSequence.length();
            int offset = 0;
            while (length > 0)
            {
                _bytes.reset();
                int chars = Math.min(length, getMaxWriteSize());

                byte[] buffer = _bytes.getBuf();
                int bytes = _bytes.getCount();

                if (bytes + chars > buffer.length)
                    chars = buffer.length - bytes;

                for (int i = 0; i < chars; i++)
                {
                    int code = charSequence.charAt(offset + i);

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

                _bytes.writeTo(_out);
                length -= chars;
                offset += chars;
            }
            return this;
        }
    }

    /**
     * An implementation of {@link WriteThroughWriter} that internally
     * uses {@link java.io.OutputStreamWriter}.
     */
    private static class EncodingWriter extends WriteThroughWriter
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
        public WriteThroughWriter append(CharSequence charSequence) throws IOException
        {
            assert charSequence.length() <= getMaxWriteSize();

            _bytes.reset();
            _converter.append(charSequence);
            _converter.flush();
            _bytes.writeTo(_out);
            return this;
        }
    }
}
