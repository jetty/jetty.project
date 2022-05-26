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

package org.eclipse.jetty.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

/**
 * ReadLineInputStream
 *
 * Read from an input stream, accepting CR/LF, LF or just CR.
 */
@Deprecated(forRemoval = true)
public class ReadLineInputStream extends BufferedInputStream
{
    boolean _seenCRLF;
    boolean _skipLF;
    private EnumSet<Termination> _lineTerminations = EnumSet.noneOf(Termination.class);

    public EnumSet<Termination> getLineTerminations()
    {
        return _lineTerminations;
    }

    public enum Termination
    {
        CRLF,
        LF,
        CR,
        EOF
    }

    public ReadLineInputStream(InputStream in)
    {
        super(in);
    }

    public ReadLineInputStream(InputStream in, int size)
    {
        super(in, size);
    }

    public String readLine() throws IOException
    {
        mark(buf.length);

        while (true)
        {
            int b = super.read();

            if (markpos < 0)
                throw new IOException("Buffer size exceeded: no line terminator");

            if (_skipLF && b != '\n')
                _lineTerminations.add(Termination.CR);

            if (b == -1)
            {
                int m = markpos;
                markpos = -1;
                if (pos > m)
                {
                    _lineTerminations.add(Termination.EOF);
                    return new String(buf, m, pos - m, StandardCharsets.UTF_8);
                }
                return null;
            }

            if (b == '\r')
            {
                int p = pos;

                // if we have seen CRLF before, hungrily consume LF
                if (_seenCRLF && pos < count)
                {
                    if (buf[pos] == '\n')
                    {
                        _lineTerminations.add(Termination.CRLF);
                        pos += 1;
                    }
                    else
                    {
                        _lineTerminations.add(Termination.CR);
                    }
                }
                else
                    _skipLF = true;

                int m = markpos;
                markpos = -1;
                return new String(buf, m, p - m - 1, StandardCharsets.UTF_8);
            }

            if (b == '\n')
            {
                if (_skipLF)
                {
                    _skipLF = false;
                    _seenCRLF = true;
                    markpos++;
                    _lineTerminations.add(Termination.CRLF);
                    continue;
                }
                int m = markpos;
                markpos = -1;
                _lineTerminations.add(Termination.LF);
                return new String(buf, m, pos - m - 1, StandardCharsets.UTF_8);
            }
        }
    }

    @Override
    public synchronized int read() throws IOException
    {
        int b = super.read();
        if (_skipLF)
        {
            _skipLF = false;
            if (_seenCRLF && b == '\n')
                b = super.read();
        }
        return b;
    }

    @Override
    public synchronized int read(byte[] buf, int off, int len) throws IOException
    {
        if (_skipLF && len > 0)
        {
            _skipLF = false;
            if (_seenCRLF)
            {
                int b = super.read();
                if (b == -1)
                    return -1;

                if (b != '\n')
                {
                    buf[off] = (byte)(0xff & b);
                    return 1 + super.read(buf, off + 1, len - 1);
                }
            }
        }

        return super.read(buf, off, len);
    }
}
