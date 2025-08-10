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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryEndPointPipeTest
{
    private final ByteBufferPool buffers = new ArrayByteBufferPool();
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
        executor.stop();
        scheduler.stop();
    }

    @Test
    public void testEndPointPiping() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, executor::execute, null);

        EndPoint localEndPoint = pipe.getLocalEndPoint();
        EndPoint remoteEndPoint = pipe.getRemoteEndPoint();

        Callback.Completable remoteFillCallback = new Callback.Completable();
        remoteEndPoint.fillInterested(remoteFillCallback);

        RetainableByteBuffer buffer = buffers.acquire(512, false);
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
                byteBuffer.position(byteBuffer.position() + filled);
                totalFilled += filled;
            }
            else
            {
                break;
            }
        }

        assertThat(totalFilled, equalTo(totalWritten));
    }

    @Test
    public void testWriteCongestedResumesWhenReading() throws Exception
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, executor::execute, null);
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
}
