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

package org.eclipse.jetty.ee11.servlet.internal;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import jakarta.servlet.ReadListener;
import org.eclipse.jetty.ee11.servlet.HttpInput;
import org.eclipse.jetty.ee11.servlet.util.ServletInputStreamWrapper;

public class UpgradedServletInputStream extends ServletInputStreamWrapper
{
    private final CompletableFuture<Void> _inputStreamComplete = new CompletableFuture<>();

    public UpgradedServletInputStream(HttpInput httpInput)
    {
        super(httpInput);
    }

    public CompletableFuture<Void> getCompletableFuture()
    {
        return _inputStreamComplete;
    }

    @Override
    public int read() throws IOException
    {
        try
        {
            int read = super.read();
            if (read == -1)
                _inputStreamComplete.complete(null);
            return read;
        }
        catch (Throwable t)
        {
            _inputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public int read(byte[] b) throws IOException
    {
        try
        {
            int read = super.read(b);
            if (read == -1)
                _inputStreamComplete.complete(null);
            return read;
        }
        catch (Throwable t)
        {
            _inputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        try
        {
            int read = super.read(b, off, len);
            if (read == -1)
                _inputStreamComplete.complete(null);
            return read;
        }
        catch (Throwable t)
        {
            _inputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            super.close();
            _inputStreamComplete.complete(null);
        }
        catch (Throwable t)
        {
            _inputStreamComplete.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        super.setReadListener(new ReadListener()
        {
            @Override
            public void onDataAvailable() throws IOException
            {
                readListener.onDataAvailable();
            }

            @Override
            public void onAllDataRead() throws IOException
            {
                try
                {
                    readListener.onAllDataRead();
                    _inputStreamComplete.complete(null);
                }
                catch (Throwable t)
                {
                    _inputStreamComplete.completeExceptionally(t);
                    throw t;
                }
            }

            @Override
            public void onError(Throwable t)
            {
                readListener.onError(t);
                _inputStreamComplete.completeExceptionally(t);
            }
        });
    }
}
