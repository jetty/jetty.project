//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.encoders;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.Encoder;

/**
 * A <code>Future&lt;Void&gt;</code> that is already failed as a result of an Encode error
 */
public class EncodeFailedFuture implements Future<Void>
{
    private final String msg;
    private final Throwable cause;

    public EncodeFailedFuture(Object data, Encoder encoder, Class<?> encoderType, Throwable cause)
    {
        this.msg = String.format("Unable to encode %s using %s as %s",data.getClass().getName(),encoder.getClass().getName(),encoderType.getName());
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
        throw new ExecutionException(msg,cause);
    }

    @Override
    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        throw new ExecutionException(msg,cause);
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
