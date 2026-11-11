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

package org.eclipse.jetty.websocket.core.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.messages.PartialByteBufferMessageSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.lang.invoke.MethodType.methodType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ByteBufferMessageSinkTest
{
    private static final String[] CHUNKS = {"Hello", " ", "World"};

    private final ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
    private final CoreSession coreSession = new CoreSession.Empty()
    {
        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return bufferPool;
        }
    };
    private final Endpoint endpoint = new Endpoint();

    @AfterEach
    public void dispose()
    {
        assertThat(bufferPool.dumpLeaks(), bufferPool.getLeaks(), empty());
    }

    @Test
    public void testWholeMessageWithMultiBufferPayload() throws Exception
    {
        MethodHandle methodHandle = MethodHandles.lookup()
            .findVirtual(Endpoint.class, "onBinary", methodType(void.class, ByteBuffer.class))
            .bindTo(endpoint);
        ByteBufferMessageSink sink = new ByteBufferMessageSink(coreSession, MethodHolder.from(methodHandle), true);

        CountingCallback callback = new CountingCallback();
        try (RetainableByteBuffer payload = newMultiBuffer(CHUNKS);
             Frame frame = new Frame(OpCode.BINARY, true, payload))
        {
            sink.accept(frame, callback);
        }
        // The frame callback must be completed exactly once.
        assertThat(callback.successes.get(), is(1));
        assertThat(callback.failures.get(), is(0));

        // The whole message must be delivered with a single invocation.
        Invocation invocation = endpoint.invocations.poll(5, TimeUnit.SECONDS);
        assertNotNull(invocation);
        assertThat(invocation.text(), is(String.join("", CHUNKS)));
        assertTrue(endpoint.invocations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPartialMessageWithMultiBufferPayload(boolean fin) throws Exception
    {
        MethodHandle methodHandle = MethodHandles.lookup()
            .findVirtual(Endpoint.class, "onPartialBinary", methodType(void.class, ByteBuffer.class, boolean.class))
            .bindTo(endpoint);
        PartialByteBufferMessageSink sink = new PartialByteBufferMessageSink(coreSession, MethodHolder.from(methodHandle), true);

        CountingCallback callback = new CountingCallback();
        try (RetainableByteBuffer payload = newMultiBuffer(CHUNKS);
             Frame frame = new Frame(OpCode.BINARY, fin, payload))
        {
            sink.accept(frame, callback);
        }
        // The frame callback must be completed exactly once.
        assertThat(callback.successes.get(), is(1));
        assertThat(callback.failures.get(), is(0));

        // Each chunk must be delivered with a separate invocation,
        // and only the last chunk of a fin frame must be last.
        for (int i = 0; i < CHUNKS.length; ++i)
        {
            Invocation invocation = endpoint.invocations.poll(5, TimeUnit.SECONDS);
            assertNotNull(invocation);
            assertThat(invocation.text(), is(CHUNKS[i]));
            assertThat(invocation.last(), is(fin && i == CHUNKS.length - 1));
        }
        assertTrue(endpoint.invocations.isEmpty());
    }

    private RetainableByteBuffer newMultiBuffer(String... chunks)
    {
        List<RetainableByteBuffer> buffers = new ArrayList<>();
        for (String chunk : chunks)
        {
            RetainableByteBuffer.Mutable buffer = bufferPool.acquire(chunk.length(), false);
            buffer.append(BufferUtil.toBuffer(chunk, UTF_8));
            buffers.add(buffer);
        }
        // The merged buffer retains slices of the chunks.
        RetainableByteBuffer result = RetainableByteBuffer.merge(buffers);
        buffers.forEach(RetainableByteBuffer::release);
        assertThat(result.getClass().getSimpleName(), is("MultiBuffer"));
        return result;
    }

    private static class CountingCallback implements Callback
    {
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void succeeded()
        {
            successes.incrementAndGet();
        }

        @Override
        public void failed(Throwable x)
        {
            failures.incrementAndGet();
        }
    }

    public record Invocation(String text, boolean last)
    {
    }

    public static class Endpoint
    {
        private final BlockingQueue<Invocation> invocations = new LinkedBlockingQueue<>();

        public void onBinary(ByteBuffer byteBuffer)
        {
            onPartialBinary(byteBuffer, true);
        }

        public void onPartialBinary(ByteBuffer byteBuffer, boolean last)
        {
            // The ByteBuffer is only valid during the invocation.
            // Consume it, like applications typically do.
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            invocations.offer(new Invocation(new String(bytes, UTF_8), last));
        }
    }
}
