//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.SendResult;

public class JsrSendResultFuture implements Future<SendResult>
{
    private final Future<Void> jettyFuture;
    
    public JsrSendResultFuture(Future<Void> jettyFuture)
    {
        this.jettyFuture = jettyFuture;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isCancelled()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isDone()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public SendResult get() throws InterruptedException, ExecutionException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SendResult get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        // TODO Auto-generated method stub
        return null;
    }
}
