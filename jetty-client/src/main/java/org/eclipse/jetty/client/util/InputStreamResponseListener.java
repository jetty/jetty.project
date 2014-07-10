//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.Listener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Implementation of {@link Listener} that produces an {@link InputStream}
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
public class InputStreamResponseListener extends Listener.Adapter
{
    private static final Logger LOG = Log.getLogger(InputStreamResponseListener.class);
    private static final byte[] EOF = new byte[0];
    private static final byte[] CLOSED = new byte[0];
    private static final byte[] FAILURE = new byte[0];
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong length = new AtomicLong();
    private final CountDownLatch responseLatch = new CountDownLatch(1);
    private final CountDownLatch resultLatch = new CountDownLatch(1);
    private final AtomicReference<InputStream> stream = new AtomicReference<>();
    private final long maxBufferSize;
    private Response response;
    private Result result;
    private volatile Throwable failure;
    private volatile boolean closed;

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
        if (!closed)
        {
            int remaining = content.remaining();
            if (remaining > 0)
            {

                byte[] bytes = new byte[remaining];
                content.get(bytes);
                if (LOG.isDebugEnabled())
                    LOG.debug("Queuing {}/{} bytes", bytes, remaining);
                queue.offer(bytes);

                long newLength = length.addAndGet(remaining);
                while (newLength >= maxBufferSize)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Queued bytes limit {}/{} exceeded, waiting", newLength, maxBufferSize);
                    // Block to avoid infinite buffering
                    if (!await())
                        break;
                    newLength = length.get();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Queued bytes limit {}/{} exceeded, woken up", newLength, maxBufferSize);
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Queuing skipped, empty content {}", content);
            }
        }
        else
        {
            LOG.debug("Queuing skipped, stream already closed");
        }
    }

    @Override
    public void onComplete(Result result)
    {
        this.result = result;
        if (result.isSucceeded())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Queuing end of content {}{}", EOF, "");
            queue.offer(EOF);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Queuing failure {} {}", FAILURE, failure);
            queue.offer(FAILURE);
            this.failure = result.getFailure();
            responseLatch.countDown();
        }
        resultLatch.countDown();
        signal();
    }

    protected boolean await()
    {
        try
        {
            synchronized (this)
            {
                while (length.get() >= maxBufferSize && failure == null && !closed)
                    wait();
                // Re-read the values as they may have changed while waiting.
                return failure == null && !closed;
            }
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    protected void signal()
    {
        synchronized (this)
        {
            notifyAll();
        }
    }

    /**
     * Waits for the given timeout for the response to be available, then returns it.
     * <p />
     * The wait ends as soon as all the HTTP headers have been received, without waiting for the content.
     * To wait for the whole content, see {@link #await(long, TimeUnit)}.
     *
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return the response
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the timeout expires
     * @throws ExecutionException if a failure happened
     */
    public Response get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException, ExecutionException
    {
        boolean expired = !responseLatch.await(timeout, unit);
        if (expired)
            throw new TimeoutException();
        if (failure != null)
            throw new ExecutionException(failure);
        return response;
    }

    /**
     * Waits for the given timeout for the whole request/response cycle to be finished,
     * then returns the corresponding result.
     * <p />
     *
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return the result
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the timeout expires
     * @see #get(long, TimeUnit)
     */
    public Result await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException
    {
        boolean expired = !resultLatch.await(timeout, unit);
        if (expired)
            throw new TimeoutException();
        return result;
    }

    /**
     * Returns an {@link InputStream} providing the response content bytes.
     * <p />
     * The method may be invoked only once; subsequent invocations will return a closed {@link InputStream}.
     *
     * @return an input stream providing the response content
     */
    public InputStream getInputStream()
    {
        InputStream result = new Input();
        if (stream.compareAndSet(null, result))
            return result;
        return IO.getClosedStream();
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
                else if (bytes == CLOSED)
                {
                    if (index < 0)
                        return -1;
                    throw new AsynchronousCloseException();
                }
                else if (bytes != null)
                {
                    int result = bytes[index] & 0xFF;
                    if (++index == bytes.length)
                    {
                        length.addAndGet(-index);
                        bytes = null;
                        index = 0;
                        signal();
                    }
                    return result;
                }
                else
                {
                    bytes = take();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dequeued {}/{} bytes", bytes, bytes.length);
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
            if (!closed)
            {
                super.close();
                if (LOG.isDebugEnabled())
                    LOG.debug("Queuing close {}{}", CLOSED, "");
                queue.offer(CLOSED);
                closed = true;
                signal();
            }
        }
    }
}
