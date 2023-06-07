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

package org.eclipse.jetty.util.component;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GracefulShutdownTest
{
    @Test
    public void testGracefulShutdown() throws Exception
    {
        AtomicBoolean isShutdown = new AtomicBoolean();
        Graceful.Shutdown shutdown = new Graceful.Shutdown("testGracefulShutdown")
        {
            @Override
            public boolean isShutdownDone()
            {
                return isShutdown.get();
            }
        };

        assertThat(shutdown.isShutdown(), is(false));

        shutdown.check();
        assertThat(shutdown.isShutdown(), is(false));

        CompletableFuture<Void> cf = shutdown.shutdown();
        assertThat(shutdown.isShutdown(), is(true));
        shutdown.check();
        assertThat(shutdown.isShutdown(), is(true));

        assertThrows(TimeoutException.class, () -> cf.get(10, TimeUnit.MILLISECONDS));

        isShutdown.set(true);
        shutdown.check();
        assertThat(shutdown.isShutdown(), is(true));
        assertThat(cf.get(), nullValue());
    }

    @Test
    public void testGracefulShutdownCancel() throws Exception
    {
        AtomicBoolean isShutdown = new AtomicBoolean();
        Graceful.Shutdown shutdown = new Graceful.Shutdown("testGracefulShutdownCancel")
        {
            @Override
            public boolean isShutdownDone()
            {
                return isShutdown.get();
            }
        };

        CompletableFuture<Void> cf1 = shutdown.shutdown();
        shutdown.cancel();
        assertThat(shutdown.isShutdown(), is(false));
        assertThrows(CancellationException.class, cf1::get);

        CompletableFuture<Void> cf2 = shutdown.shutdown();
        assertThat(shutdown.isShutdown(), is(true));
        isShutdown.set(true);
        assertThrows(TimeoutException.class, () -> cf2.get(10, TimeUnit.MILLISECONDS));
        shutdown.check();
        assertThat(shutdown.isShutdown(), is(true));
        assertThat(cf2.get(), nullValue());
    }
}
