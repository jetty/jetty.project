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

package org.eclipse.jetty.websocket.common.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.util.MethodHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.lang.invoke.MethodType.methodType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @AfterEach
    public void dispose()
    {
        assertThat(bufferPool.dumpLeaks(), bufferPool.getLeaks(), empty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testWholeMessageWithMultiBufferPayload(boolean sync) throws Exception
    {
        Endpoint endpoint = new Endpoint(sync);
        MethodHandle methodHandle = MethodHandles.lookup()
            .findVirtual(Endpoint.class, "onBinary", methodType(void.class, ByteBuffer.class, Callback.class))
            .bindTo(endpoint);
        ByteBufferMessageSink sink = new ByteBufferMessageSink(coreSession, MethodHolder.from(methodHandle), true);

        FutureCallback callback = new FutureCallback();
        try (RetainableByteBuffer payload = newMultiBuffer(CHUNKS);
             Frame frame = new Frame(OpCode.BINARY, true, payload))
        {
            sink.accept(frame, callback);
        }

        // The whole message must be delivered with a single invocation.
        Invocation invocation = endpoint.invocations.poll(5, TimeUnit.SECONDS);
        assertNotNull(invocation);
        assertThat(invocation.text(), is(String.join("", CHUNKS)));
        if (!sync)
        {
            // The ByteBuffer must be valid until the application completes the callback.
            assertFalse(callback.isDone());
            assertThat(BufferUtil.toString(invocation.byteBuffer(), UTF_8), is(invocation.text()));
            invocation.callback().succeed();
        }

        callback.get(5, TimeUnit.SECONDS);
        assertTrue(endpoint.invocations.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    public void testPartialMessageWithMultiBufferPayload(boolean sync, boolean fin) throws Exception
    {
        Endpoint endpoint = new Endpoint(sync);
        MethodHandle methodHandle = MethodHandles.lookup()
            .findVirtual(Endpoint.class, "onPartialBinary", methodType(void.class, ByteBuffer.class, boolean.class, Callback.class))
            .bindTo(endpoint);
        PartialByteBufferMessageSink sink = new PartialByteBufferMessageSink(coreSession, MethodHolder.from(methodHandle), true);

        FutureCallback callback = new FutureCallback();
        try (RetainableByteBuffer payload = newMultiBuffer(CHUNKS);
             Frame frame = new Frame(OpCode.BINARY, fin, payload))
        {
            sink.accept(frame, callback);
        }

        // Each chunk must be delivered with a separate invocation,
        // and only the last chunk of a fin frame must be last.
        for (int i = 0; i < CHUNKS.length; ++i)
        {
            Invocation invocation = endpoint.invocations.poll(5, TimeUnit.SECONDS);
            assertNotNull(invocation);
            assertThat(invocation.text(), is(CHUNKS[i]));
            assertThat(invocation.last(), is(fin && i == CHUNKS.length - 1));
            if (!sync)
            {
                // The next chunk must not be delivered until the application completes the callback.
                assertFalse(callback.isDone());
                assertTrue(endpoint.invocations.isEmpty());
                // The ByteBuffer must be valid until the application completes the callback.
                assertThat(BufferUtil.toString(invocation.byteBuffer(), UTF_8), is(CHUNKS[i]));
                invocation.callback().succeed();
            }
        }

        callback.get(5, TimeUnit.SECONDS);
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

    public record Invocation(String text, ByteBuffer byteBuffer, boolean last, Callback callback)
    {
    }

    public static class Endpoint
    {
        private final BlockingQueue<Invocation> invocations = new LinkedBlockingQueue<>();
        private final boolean sync;

        public Endpoint(boolean sync)
        {
            this.sync = sync;
        }

        public void onBinary(ByteBuffer byteBuffer, Callback callback)
        {
            onPartialBinary(byteBuffer, true, callback);
        }

        public void onPartialBinary(ByteBuffer byteBuffer, boolean last, Callback callback)
        {
            invocations.offer(new Invocation(BufferUtil.toString(byteBuffer, UTF_8), byteBuffer, last, callback));
            if (sync)
                callback.succeed();
        }
    }
}
