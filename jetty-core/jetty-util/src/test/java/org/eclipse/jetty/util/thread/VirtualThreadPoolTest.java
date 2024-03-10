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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledForJreRange(max = JRE.JAVA_20)
public class VirtualThreadPoolTest
{
    @Test
    public void testNamed() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        assertThat(vtp.getName(), startsWith("vtp"));
        vtp.setName("namedV");
        vtp.start();

        CompletableFuture<String> name = new CompletableFuture<>();
        vtp.execute(() -> name.complete(Thread.currentThread().getName()));

        assertThat(name.get(5, TimeUnit.SECONDS), startsWith("namedV"));

        vtp.stop();
    }

    @Test
    public void testJoin() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        assertThat(vtp.getName(), startsWith("vtp"));
        vtp.start();

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);

        vtp.execute(() ->
        {
            try
            {
                running.countDown();
                vtp.join();
                joined.countDown();
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t);
            }
        });

        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertThat(joined.getCount(), is(1L));
        vtp.stop();
        assertTrue(joined.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testExecute() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        assertThat(vtp.getName(), startsWith("vtp"));
        vtp.start();

        CountDownLatch ran = new CountDownLatch(1);
        vtp.execute(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        vtp.stop();
    }

    @Test
    public void testTry() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        assertThat(vtp.getName(), startsWith("vtp"));
        vtp.start();

        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(vtp.tryExecute(ran::countDown));
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        vtp.stop();
    }

    @Test
    public void testThread() throws Exception
    {
        VirtualThreadPool vtp = new VirtualThreadPool();
        assertThat(vtp.getName(), startsWith("vtp"));
        vtp.start();

        CountDownLatch ran = new CountDownLatch(1);
        Thread t = vtp.newThread(ran::countDown);
        assertThat(t.getName(), startsWith(vtp.getName()));
        assertFalse(ran.await(1, TimeUnit.SECONDS));
        t.start();
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        vtp.stop();
    }
}
