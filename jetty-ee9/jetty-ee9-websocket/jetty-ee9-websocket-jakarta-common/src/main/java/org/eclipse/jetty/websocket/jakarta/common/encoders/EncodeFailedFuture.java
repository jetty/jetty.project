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

package org.eclipse.jetty.websocket.jakarta.common.encoders;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.websocket.Encoder;

/**
 * A {@code Future&lt;Void&gt;} that is already failed as a result of an Encode error
 */
public class EncodeFailedFuture implements Future<Void>
{
    private final String msg;
    private final Throwable cause;

    public EncodeFailedFuture(Object data, Encoder encoder, Class<?> encoderType, Throwable cause)
    {
        this.msg = String.format("Unable to encode %s using %s as %s", data.getClass().getName(), encoder.getClass().getName(), encoderType.getName());
        this.cause = cause;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        return false;
    }

    @Override
    public Void get() throws InterruptedException, ExecutionException
    {
        throw new ExecutionException(msg, cause);
    }

    @Override
    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        throw new ExecutionException(msg, cause);
    }

    @Override
    public boolean isCancelled()
    {
        return false;
    }

    @Override
    public boolean isDone()
    {
        return true;
    }
}
