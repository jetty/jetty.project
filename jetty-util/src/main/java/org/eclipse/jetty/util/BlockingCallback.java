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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BlockingCallback implements Callback
{
    private FutureCallback callback = new FutureCallback();
    private final long timeout;

    public BlockingCallback()
    {
        this(-1);
    }

    public BlockingCallback(long timeout)
    {
        this.timeout = timeout;
    }

    @Override
    public void succeeded()
    {
        callback.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        callback.failed(x);
    }

    public void block() throws IOException
    {
        try
        {
            if (timeout > 0)
                callback.get(timeout, TimeUnit.MILLISECONDS);
            else
                callback.get();
        }
        catch (InterruptedException e)
        {
            InterruptedIOException exception = new InterruptedIOException();
            exception.initCause(e);
            throw exception;
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            else
                throw new IOException(cause);
        }
        catch (TimeoutException e)
        {
            throw new IOException(e);
        }
    }
}
