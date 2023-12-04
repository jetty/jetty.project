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

package org.eclipse.jetty.ee10.servlet;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static java.nio.charset.StandardCharsets.US_ASCII;

public abstract class AbstractContentProducerTest
{
    private TimerScheduler _scheduler;

    @BeforeEach
    public void setUp() throws Exception
    {
        _scheduler = new TimerScheduler();
        _scheduler.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _scheduler.stop();
    }

    static int countRemaining(List<Content.Chunk> chunks)
    {
        int total = 0;
        for (Content.Chunk chunk : chunks)
        {
            total += chunk.remaining();
        }
        return total;
    }

    static String asString(List<Content.Chunk> chunks)
    {
        StringBuilder sb = new StringBuilder();
        for (Content.Chunk chunk : chunks)
        {
            byte[] b = new byte[chunk.remaining()];
            chunk.getByteBuffer().duplicate().get(b);
            sb.append(new String(b, US_ASCII));
        }
        return sb.toString();
    }

    class ArrayDelayedServletChannel extends ServletChannel
    {
        ArrayDelayedServletChannel(List<Content.Chunk> chunks)
        {
            super(new ServletContextHandler(), new MockConnectionMetaData());
            associate(new ArrayDelayedServletChannelRequest(chunks), null, Callback.NOOP);
        }

        ContentProducer getAsyncContentProducer()
        {
            return _httpInput._asyncContentProducer;
        }

        ContentProducer getBlockingContentProducer()
        {
            return _httpInput._blockingContentProducer;
        }

        BooleanSupplier getContentPresenceCheckSupplier()
        {
            return () -> !getServletRequestState().isInputUnready();
        }

        AutoLock getLock()
        {
            return _httpInput._lock;
        }
    }

    private class ArrayDelayedServletChannelRequest extends MockRequest
    {
        private final List<Content.Chunk> chunks;
        private int counter;
        private volatile Content.Chunk nextContent;

        ArrayDelayedServletChannelRequest(List<Content.Chunk> chunks)
        {
            for (int i = 0; i < chunks.size() - 1; i++)
            {
                Content.Chunk chunk = chunks.get(i);
                if (chunk.isLast())
                    throw new AssertionError("Only the last of the given chunks may be marked as last");
            }
            if (!chunks.get(chunks.size() - 1).isLast())
                throw new AssertionError("The last of the given chunks must be marked as last");
            this.chunks = chunks;
        }

        @Override
        public void fail(Throwable failure)
        {
            nextContent = Content.Chunk.from(failure, true);
            counter = chunks.size();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (nextContent != null)
            {
                demandCallback.run();
                return;
            }

            _scheduler.schedule(() ->
            {
                int idx = counter < chunks.size() ? counter++ : chunks.size() - 1;
                nextContent = chunks.get(idx);
                demandCallback.run();
            }, 50, TimeUnit.MILLISECONDS);
        }

        @Override
        public Content.Chunk read()
        {
            Content.Chunk result = nextContent;
            nextContent = null;
            return result;
        }
    }
}
