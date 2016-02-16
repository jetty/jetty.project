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

package org.eclipse.jetty.websocket.common.io;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.WriteCallback;

/**
 * Tracking Callback for testing how the callbacks are used.
 */
public class TrackingCallback implements Callback, WriteCallback
{
    private AtomicInteger called = new AtomicInteger();
    private boolean success = false;
    private Throwable failure = null;

    @Override
    public void failed(Throwable x)
    {
        this.called.incrementAndGet();
        this.success = false;
        this.failure = x;
    }

    @Override
    public void succeeded()
    {
        this.called.incrementAndGet();
        this.success = false;
    }

    public Throwable getFailure()
    {
        return failure;
    }

    public boolean isSuccess()
    {
        return success;
    }

    public boolean isCalled()
    {
        return called.get() >= 1;
    }

    public int getCallCount()
    {
        return called.get();
    }

    @Override
    public void writeFailed(Throwable x)
    {
        failed(x);
    }

    @Override
    public void writeSuccess()
    {
        succeeded();
    }
}
