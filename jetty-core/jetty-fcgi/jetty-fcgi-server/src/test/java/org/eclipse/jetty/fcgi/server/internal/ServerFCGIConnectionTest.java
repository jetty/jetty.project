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

package org.eclipse.jetty.fcgi.server.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerFCGIConnectionTest
{
    @Test
    public void testCompletionWaitsForFill() throws Exception
    {
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean releasedDuringFill = new AtomicBoolean();
        ByteBufferPool bufferPool = new ByteBufferPool.Wrapper(ByteBufferPool.NON_POOLING)
        {
            @Override
            public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
            {
                return new RetainableByteBuffer.Mutable.Wrapper(super.acquire(size, direct))
                {
                    @Override
                    public boolean release()
                    {
                        released.set(true);
                        return super.release();
                    }
                };
            }
        };

        CountDownLatch fillEntered = new CountDownLatch(1);
        CountDownLatch fillProceed = new CountDownLatch(1);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint()
        {
            @Override
            public int fill(ByteBuffer buffer) throws IOException
            {
                fillEntered.countDown();
                try
                {
                    assertTrue(fillProceed.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException x)
                {
                    throw new IOException(x);
                }
                releasedDuringFill.set(released.get());
                return super.fill(buffer);
            }
        };

        Server server = new Server(null, null, bufferPool);
        ServerConnector connector = new ServerConnector(server);
        ServerFCGIConnection connection = new ServerFCGIConnection(connector, endPoint, new HttpConfiguration(), false);
        AtomicReference<Throwable> fillFailure = new AtomicReference<>();
        Thread fillThread = new Thread(() -> run(connection::onFillable, fillFailure));
        fillThread.start();
        assertTrue(fillEntered.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> completionFailure = new AtomicReference<>();
        Thread completionThread = new Thread(() -> run(() -> connection.onCompleted(new IOException("test")), completionFailure));
        completionThread.start();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !completionThread.isAlive() || completionThread.getState() == Thread.State.WAITING || completionThread.getState() == Thread.State.BLOCKED);

        try
        {
            assertTrue(completionThread.isAlive());
        }
        finally
        {
            fillProceed.countDown();
            fillThread.join(5000);
            completionThread.join(5000);
        }

        assertFalse(fillThread.isAlive());
        assertFalse(completionThread.isAlive());
        assertFalse(releasedDuringFill.get());
        assertNull(fillFailure.get());
        assertNull(completionFailure.get());
    }

    private static void run(Runnable action, AtomicReference<Throwable> failure)
    {
        try
        {
            action.run();
        }
        catch (Throwable x)
        {
            failure.set(x);
        }
    }
}
