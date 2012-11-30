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
import java.nio.channels.AsynchronousCloseException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Implementation of {@link Response.Listener} that produces an {@link InputStream}
 * that allows applications to read the response content.
 * <p />
 * Typical usage is:
 * <pre>
 * InputStreamResponseListener listener = new InputStreamResponseListener();
 * client.newRequest(...).send(listener);
 *
 * // Wait for the response headers to arrive
 * Response response = listener.get(5, TimeUnit.SECONDS);
 * if (response.getStatus() == 200)
 * {
 *     // Obtain the input stream on the response content
 *     try (InputStream input = listener.getInputStream())
 *     {
 *         // Read the response content
 *     }
 * }
 * </pre>
 * <p />
 * The {@link HttpClient} implementation (the producer) will feed the input stream
 * asynchronously while the application (the consumer) is reading from it.
 * Chunks of content are maintained in a queue, and it is possible to specify a
 * maximum buffer size for the bytes held in the queue, by default 16384 bytes.
 * <p />
 * If the consumer is faster than the producer, then the consumer will block
 * with the typical {@link InputStream#read()} semantic.
 * If the consumer is slower than the producer, then the producer will block
 * until the client consumes.
 */
public class InputStreamResponseListener extends Response.Listener.Empty
{
    private static final Logger LOG = Log.getLogger(InputStreamResponseListener.class);
    private static final byte[] EOF = new byte[0];
    private static final byte[] CLOSE = new byte[0];
    private static final byte[] FAILURE = new byte[0];
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong length = new AtomicLong();
    private final CountDownLatch responseLatch = new CountDownLatch(1);
    private final CountDownLatch resultLatch = new CountDownLatch(1);
    private final long maxBufferSize;
    private Response response;
    private Result result;
    private volatile Throwable failure;

    public InputStreamResponseListener()
    {
        this(16 * 1024L);
    }

    public InputStreamResponseListener(long maxBufferSize)
    {
        this.maxBufferSize = maxBufferSize;
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
        LOG.debug("Queuing {}/{} bytes", bytes, bytes.length);
        queue.offer(bytes);

        long newLength = length.addAndGet(remaining);
        while (newLength >= maxBufferSize)
        {
            LOG.debug("Queued bytes limit {}/{} exceeded, waiting", newLength, maxBufferSize);
            if (!await())
                break;
            newLength = length.get();
            LOG.debug("Queued bytes limit {}/{} exceeded, woken up", newLength, maxBufferSize);
        }
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        this.failure = failure;
        LOG.debug("Queuing failure {} {}", FAILURE, failure);
        queue.offer(FAILURE);
        responseLatch.countDown();
    }

    @Override
    public void onSuccess(Response response)
    {
        LOG.debug("Queuing end of content {}{}", EOF, "");
        queue.offer(EOF);
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
                if (bytes == EOF)
                {
                    // Mark the fact that we saw -1,
                    // so that in the close case we don't throw
                    index = -1;
                    return -1;
                }
                else if (bytes == FAILURE)
                {
                    throw failure();
                }
                else if (bytes == CLOSE)
                {
                    if (index < 0)
                        return -1;
                    throw new AsynchronousCloseException();
                }
                else if (bytes != null)
                {
                    if (index < bytes.length)
                        return bytes[index++];
                    length.addAndGet(-index);
                    bytes = null;
                    index = 0;
                }
                else
                {
                    bytes = take();
                    LOG.debug("Dequeued {}/{} bytes", bytes, bytes.length);
                    signal();
                }
            }
        }

        private IOException failure()
        {
            if (failure instanceof IOException)
                return (IOException)failure;
            else
                return new IOException(failure);
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

        @Override
        public void close() throws IOException
        {
            LOG.debug("Queuing close {}{}", CLOSE, "");
            queue.offer(CLOSE);
            super.close();
        }
    }
}
