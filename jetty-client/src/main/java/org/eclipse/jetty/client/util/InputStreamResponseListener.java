//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

public class InputStreamResponseListener extends Response.Listener.Empty
{
    private final AtomicLong length = new AtomicLong();
    private final CountDownLatch responseLatch = new CountDownLatch(1);
    private final long capacity;
    private Response response;
    private volatile Throwable failure;

    public InputStreamResponseListener()
    {
        this(1024 * 1024L);
    }

    public InputStreamResponseListener(long capacity)
    {
        this.capacity = capacity;
    }

    @Override
    public void onHeaders(Response response)
    {
        this.response = response;
        responseLatch.countDown();
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        int remaining = content.remaining();
        byte[] bytes = new byte[remaining];
        content.get(bytes);

        long newLength = length.addAndGet(remaining);
        if (newLength > capacity)
            // wait
            ;
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        this.failure = failure;
        responseLatch.countDown();
    }

    public Response get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException, ExecutionException
    {
        boolean expired = !responseLatch.await(timeout, unit);
        if (expired)
            throw new TimeoutException();
        if (failure != null)
            throw new ExecutionException(failure);
        return response;
    }

    public Result await(long timeout, TimeUnit seconds)
    {
        return null;
    }

    public InputStream getInputStream()
    {
        return new Input();
    }

    private class Input extends InputStream
    {
        @Override
        public int read() throws IOException
        {
            return 0;
        }
    }
}
