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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryEndPointPipeTest
{
    private final ArrayByteBufferPool.Tracking buffers = new ArrayByteBufferPool.Tracking();
    private final ScheduledExecutorScheduler scheduler = new ScheduledExecutorScheduler();
    private final QueuedThreadPool executor = new QueuedThreadPool();

    @BeforeEach
    public void prepare() throws Exception
    {
        scheduler.start();
        executor.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
            assertThat("buffer leaks: " + buffers.dumpLeaks(), buffers.getLeaks().size(), is(0))
        );
        executor.stop();
        scheduler.stop();
    }

    @Test
    public void testEndPointPiping() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        Callback.Completable remoteFillCallback = new Callback.Completable();
        remoteEndPoint.fillInterested(remoteFillCallback);

        RetainableByteBuffer buffer = buffers.acquire(512, false);
        try
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();

            byte[] smallZeros = new byte[byteBuffer.capacity() / 2];
            byte[] largeOnes = new byte[byteBuffer.capacity() * 2];
            Arrays.fill(largeOnes, (byte)1);
            Blocker.Shared blocker = new Blocker.Shared();
            try (Blocker.Callback callback = blocker.callback())
            {
                localEndPoint.write(callback, ByteBuffer.wrap(smallZeros));
                callback.block();
            }
            try (Blocker.Callback callback = blocker.callback())
            {
                localEndPoint.write(callback, ByteBuffer.wrap(largeOnes));
                callback.block();
            }
            int totalWritten = smallZeros.length + largeOnes.length;

            remoteFillCallback.get(5, TimeUnit.SECONDS);

            int totalFilled = 0;
            while (true)
            {
                int filled = remoteEndPoint.fill(byteBuffer);
                if (filled > 0)
                {
                    BufferUtil.clear(byteBuffer);
                    totalFilled += filled;
                }
                else
                {
                    break;
                }
            }

            assertThat(totalFilled, equalTo(totalWritten));
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void testWriteCongestedResumesWhenReading() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);
        pipe.setLocalEndPointMaxCapacity(1024);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        Callback.Completable remoteFillCallback = new Callback.Completable();
        remoteEndPoint.fillInterested(remoteFillCallback);

        Callback.Completable localWriteCallback = new Callback.Completable();
        int totalWritten = 2048;
        localEndPoint.write(localWriteCallback, ByteBuffer.allocate(totalWritten));
        localWriteCallback.thenRun(localEndPoint::close);

        assertTrue(((AbstractEndPoint)localEndPoint).getWriteFlusher().isPending());

        remoteFillCallback.get(5, TimeUnit.SECONDS);

        RetainableByteBuffer buffer = buffers.acquire(512, false);
        try
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int totalFilled = 0;
            while (true)
            {
                int filled = remoteEndPoint.fill(byteBuffer);
                if (filled > 0)
                {
                    byteBuffer.position(byteBuffer.position() + filled);
                    totalFilled += filled;
                }
                else if (filled == 0)
                {
                    try (Blocker.Callback callback = Blocker.callback())
                    {
                        remoteEndPoint.fillInterested(callback);
                        callback.block();
                    }
                }
                else
                {
                    break;
                }
            }

            assertThat(totalFilled, equalTo(totalWritten));
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void testEofAfterAllDataConsumed() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        // Register fill interest before writing.
        Callback.Completable remoteFillCallback = new Callback.Completable();
        remoteEndPoint.fillInterested(remoteFillCallback);

        byte[] data = new byte[100];
        Arrays.fill(data, (byte)42);
        Blocker.Shared blocker = new Blocker.Shared();
        try (Blocker.Callback callback = blocker.callback())
        {
            localEndPoint.write(callback, ByteBuffer.wrap(data));
            callback.block();
        }

        // Shutdown output to signal EOF.
        localEndPoint.shutdownOutput();

        // Wait for data to be available.
        remoteFillCallback.get(5, TimeUnit.SECONDS);

        RetainableByteBuffer buffer = buffers.acquire(2 * data.length, false);
        try
        {
            ByteBuffer readBuffer = buffer.getByteBuffer();
            int totalFilled = 0;
            while (true)
            {
                int filled = remoteEndPoint.fill(readBuffer);
                if (filled >= 0)
                    totalFilled += filled;
                else
                    break;
            }

            // Verify all data was read.
            assertThat(totalFilled, equalTo(data.length));

            assertThat(remoteEndPoint.fill(readBuffer), equalTo(-1));
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void testShutdownOutput() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        // Shutdown output without writing any data.
        localEndPoint.shutdownOutput();
        assertTrue(localEndPoint.isOutputShutdown());

        // Remote endpoint should get EOF immediately
        ByteBuffer readBuffer = ByteBuffer.allocate(100);
        int filled = remoteEndPoint.fill(readBuffer);
        assertThat(filled, equalTo(-1));
    }

    @Test
    public void testCloseEndpoint() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        // Register fill interest before writing.
        Callback.Completable remoteFillCallback = new Callback.Completable();
        remoteEndPoint.fillInterested(remoteFillCallback);

        // Write some data
        byte[] data = new byte[50];
        Blocker.Shared blocker = new Blocker.Shared();
        try (Blocker.Callback callback = blocker.callback())
        {
            localEndPoint.write(callback, ByteBuffer.wrap(data));
            callback.block();
        }

        // Close the local endpoint.
        localEndPoint.close();
        assertFalse(localEndPoint.isOpen());

        // Wait for data to be available.
        remoteFillCallback.get(5, TimeUnit.SECONDS);

        // Remote endpoint should be able to read existing data.
        RetainableByteBuffer buffer = buffers.acquire(2 * data.length, false);
        try
        {
            ByteBuffer readBuffer = buffer.getByteBuffer();
            int filled = remoteEndPoint.fill(readBuffer);
            assertThat(filled, equalTo(data.length));

            // After reading all data, should get EOF.
            filled = remoteEndPoint.fill(readBuffer);
            assertThat(filled, equalTo(-1));
        }
        finally
        {
            buffer.release();
        }
    }

    @Test
    public void testFillOnClosedEndpoint()
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        // Close the remote endpoint.
        remoteEndPoint.close();

        // fill() on closed endpoint should throw IOException.
        ByteBuffer readBuffer = ByteBuffer.allocate(100);
        assertThrows(IOException.class, () -> remoteEndPoint.fill(readBuffer));
    }

    @Test
    public void testFlushOnClosedEndpoint()
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();

        // Close the local endpoint.
        localEndPoint.close();

        // flush() on closed endpoint should throw IOException.
        ByteBuffer writeBuffer = ByteBuffer.wrap(new byte[50]);
        assertThrows(IOException.class, () -> localEndPoint.flush(writeBuffer));
    }

    @Test
    public void testSmallCapacityPartialFlush() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);
        // Set a small capacity that is less than the data we want to write.
        int maxCapacity = 20;
        pipe.setLocalEndPointMaxCapacity(maxCapacity);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        // Try to flush more data than capacity allows.
        byte[] bytes = new byte[2 * maxCapacity + maxCapacity / 2];
        ByteBuffer writeBuffer = ByteBuffer.wrap(bytes);
        boolean flushed = localEndPoint.flush(writeBuffer);
        assertFalse(flushed);

        // Only maxCapacity bytes should have been flushed.
        assertThat(writeBuffer.remaining(), equalTo(bytes.length - maxCapacity));

        // Complete the flush to release buffers.
        while (true)
        {
            remoteEndPoint.fill(BufferUtil.allocate(maxCapacity));
            if (flushed)
                break;
            flushed = localEndPoint.flush(writeBuffer);
        }
    }

    @Test
    public void testEmptyBufferFlush() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, buffers, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        // Flush empty buffers.
        ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
        boolean flushed = localEndPoint.flush(emptyBuffer);
        assertTrue(flushed);

        // Flush with position == limit (consumed buffer).
        ByteBuffer consumedBuffer = ByteBuffer.allocate(50);
        consumedBuffer.position(consumedBuffer.limit());
        flushed = localEndPoint.flush(consumedBuffer);
        assertTrue(flushed);

        // Remote should have no data to read.
        ByteBuffer readBuffer = ByteBuffer.allocate(100);
        int filled = remoteEndPoint.fill(readBuffer);
        assertThat(filled, equalTo(0));
    }
}
