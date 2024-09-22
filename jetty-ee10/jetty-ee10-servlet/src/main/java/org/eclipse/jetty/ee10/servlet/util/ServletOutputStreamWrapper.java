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

package org.eclipse.jetty.ee10.servlet.util;

import java.io.IOException;

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
    public boolean isReady()
    {
        return _outputStream.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        _outputStream.setWriteListener(writeListener);
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
