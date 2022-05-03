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

package org.eclipse.jetty.ee10.servlet.util;

import java.io.IOException;
import java.io.OutputStream;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

public class ServletOutputStreamWrapper extends ServletOutputStream
{
    private final ServletOutputStream _outputStream;

    public ServletOutputStreamWrapper(ServletOutputStream outputStream)
    {
        _outputStream = outputStream;
    }

    @Override
    public void print(String s) throws IOException
    {
        _outputStream.print(s);
    }

    @Override
    public void print(boolean b) throws IOException
    {
        _outputStream.print(b);
    }

    @Override
    public void print(char c) throws IOException
    {
        _outputStream.print(c);
    }

    @Override
    public void print(int i) throws IOException
    {
        _outputStream.print(i);
    }

    @Override
    public void print(long l) throws IOException
    {
        _outputStream.print(l);
    }

    @Override
    public void print(float f) throws IOException
    {
        _outputStream.print(f);
    }

    @Override
    public void print(double d) throws IOException
    {
        _outputStream.print(d);
    }

    @Override
    public void println() throws IOException
    {
        _outputStream.println();
    }

    @Override
    public void println(String s) throws IOException
    {
        _outputStream.println(s);
    }

    @Override
    public void println(boolean b) throws IOException
    {
        _outputStream.println(b);
    }

    @Override
    public void println(char c) throws IOException
    {
        _outputStream.println(c);
    }

    @Override
    public void println(int i) throws IOException
    {
        _outputStream.println(i);
    }

    @Override
    public void println(long l) throws IOException
    {
        _outputStream.println(l);
    }

    @Override
    public void println(float f) throws IOException
    {
        _outputStream.println(f);
    }

    @Override
    public void println(double d) throws IOException
    {
        _outputStream.println(d);
    }

    @Override
    public boolean isReady()
    {
        return _outputStream.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        _outputStream.setWriteListener(writeListener);
    }

    public static OutputStream nullOutputStream()
    {
        return OutputStream.nullOutputStream();
    }

    @Override
    public void write(int b) throws IOException
    {
        _outputStream.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException
    {
        _outputStream.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        _outputStream.write(b, off, len);
    }

    @Override
    public void flush() throws IOException
    {
        _outputStream.flush();
    }

    @Override
    public void close() throws IOException
    {
        _outputStream.close();
    }
}
