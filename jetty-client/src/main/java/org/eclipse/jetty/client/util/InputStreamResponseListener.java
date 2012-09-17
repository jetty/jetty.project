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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class InputStreamResponseListener extends Response.Listener.Empty
{
    public static final Logger LOG = Log.getLogger(InputStreamResponseListener.class);
    private static final byte[] EOF = new byte[0];
    private static final byte[] FAILURE = new byte[0];
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong length = new AtomicLong();
    private final CountDownLatch responseLatch = new CountDownLatch(1);
    private final CountDownLatch resultLatch = new CountDownLatch(1);
    private final long capacity;
    private Response response;
    private Result result;
    private volatile Throwable failure;

    public InputStreamResponseListener()
    {
        this(16 * 1024L);
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
        LOG.debug("Queued {}/{} bytes", bytes, bytes.length);
        queue.offer(bytes);

        long newLength = length.addAndGet(remaining);
        while (newLength >= capacity)
        {
            LOG.debug("Queued bytes limit {}/{} exceeded, waiting", newLength, capacity);
            if (!await())
                break;
            newLength = length.get();
            LOG.debug("Queued bytes limit {}/{} exceeded, woken up", newLength, capacity);
        }
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        this.failure = failure;
        queue.offer(FAILURE);
        LOG.debug("Queued failure {} {}", FAILURE, failure);
        responseLatch.countDown();
    }

    @Override
    public void onSuccess(Response response)
    {
        queue.offer(EOF);
        LOG.debug("Queued end of content {}{}", EOF, "");
    }

    @Override
    public void onComplete(Result result)
    {
        this.result = result;
        resultLatch.countDown();
    }

    private boolean await()
    {
        try
        {
            synchronized (this)
            {
                wait();
            }
            return true;
        }
        catch (InterruptedException x)
        {
            return false;
        }
    }

    private void signal()
    {
        synchronized (this)
        {
            notify();
        }
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

    public Result await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException
    {
        boolean expired = !resultLatch.await(timeout, unit);
        if (expired)
            throw new TimeoutException();
        return result;
    }

    public InputStream getInputStream()
    {
        return new Input();
    }

    private class Input extends InputStream
    {
        private byte[] bytes;
        private int index;

        @Override
        public int read() throws IOException
        {
            while (true)
            {
                if (bytes != null)
                {
                    if (index < bytes.length)
                        return bytes[index++];
                    length.addAndGet(-index);
                    bytes = null;
                    index = 0;
                }

                bytes = take();
                LOG.debug("Dequeued {}/{} bytes", bytes, bytes.length);
                if (bytes == EOF)
                    return -1;
                if (bytes == FAILURE)
                {
                    if (failure instanceof IOException)
                        throw (IOException)failure;
                    else
                        throw new IOException(failure);
                }

                signal();
            }
        }

        private byte[] take() throws IOException
        {
            try
            {
                return queue.take();
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
        }
    }
}
