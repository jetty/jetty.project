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

package org.eclipse.jetty.ee11.servlet.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

public class ServletInputStreamWrapper extends ServletInputStream
{
    private final ServletInputStream _servletInputStream;

    public ServletInputStreamWrapper(ServletInputStream servletInputStream)
    {
        _servletInputStream = servletInputStream;
    }

    @Override
    public int readLine(byte[] b, int off, int len) throws IOException
    {
        return _servletInputStream.readLine(b, off, len);
    }

    @Override
    public boolean isFinished()
    {
        return _servletInputStream.isFinished();
    }

    @Override
    public boolean isReady()
    {
        return _servletInputStream.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        _servletInputStream.setReadListener(readListener);
    }

    public static InputStream nullInputStream()
    {
        return InputStream.nullInputStream();
    }

    @Override
    public int read() throws IOException
    {
        return _servletInputStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException
    {
        return _servletInputStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        return _servletInputStream.read(b, off, len);
    }

    @Override
    public byte[] readAllBytes() throws IOException
    {
        return _servletInputStream.readAllBytes();
    }

    @Override
    public byte[] readNBytes(int len) throws IOException
    {
        return _servletInputStream.readNBytes(len);
    }

    @Override
    public int readNBytes(byte[] b, int off, int len) throws IOException
    {
        return _servletInputStream.readNBytes(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException
    {
        return _servletInputStream.skip(n);
    }

    @Override
    public void skipNBytes(long n) throws IOException
    {
        _servletInputStream.skipNBytes(n);
    }

    @Override
    public int available() throws IOException
    {
        return _servletInputStream.available();
    }

    @Override
    public void close() throws IOException
    {
        _servletInputStream.close();
    }

    @Override
    public void mark(int readlimit)
    {
        _servletInputStream.mark(readlimit);
    }

    @Override
    public void reset() throws IOException
    {
        _servletInputStream.reset();
    }

    @Override
    public boolean markSupported()
    {
        return _servletInputStream.markSupported();
    }

    @Override
    public long transferTo(OutputStream out) throws IOException
    {
        return _servletInputStream.transferTo(out);
    }
}
