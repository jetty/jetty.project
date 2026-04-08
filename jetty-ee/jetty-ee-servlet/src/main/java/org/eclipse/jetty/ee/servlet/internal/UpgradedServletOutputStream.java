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

package org.eclipse.jetty.ee.servlet.internal;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import jakarta.servlet.WriteListener;
import org.eclipse.jetty.ee.servlet.HttpOutput;
import org.eclipse.jetty.ee.servlet.util.ServletOutputStreamWrapper;

public class UpgradedServletOutputStream extends ServletOutputStreamWrapper
{
    private final CompletableFuture<Void> _outputStreamComplete = new CompletableFuture<>();

    public UpgradedServletOutputStream(HttpOutput servletChannel)
    {
        super(servletChannel);
    }

    public CompletableFuture<Void> getCompletableFuture()
    {
        return _outputStreamComplete;
    }

    @Override
    public void write(int b) throws IOException
    {
        try
        {
            super.write(b);
        }
        catch (Throwable t)
        {
            _outputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void write(byte[] b) throws IOException
    {
        try
        {
            super.write(b);
        }
        catch (Throwable t)
        {
            _outputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        try
        {
            super.write(b, off, len);
        }
        catch (Throwable t)
        {
            _outputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void flush() throws IOException
    {
        try
        {
            super.flush();
        }
        catch (Throwable t)
        {
            _outputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            super.close();
            _outputStreamComplete.complete(null);
        }
        catch (Throwable t)
        {
            _outputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        super.setWriteListener(new WriteListener()
        {
            @Override
            public void onWritePossible() throws IOException
            {
                writeListener.onWritePossible();
            }

            @Override
            public void onError(Throwable t)
            {
                writeListener.onError(t);
                _outputStreamComplete.completeExceptionally(t);
            }
        });
    }
}
