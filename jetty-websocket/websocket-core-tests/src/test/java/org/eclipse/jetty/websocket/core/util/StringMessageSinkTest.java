//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.internal.messages.StringMessageSink;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StringMessageSinkTest
{
    private CoreSession coreSession = new CoreSession.Empty();
    private OnMessageEndpoint endpoint = new OnMessageEndpoint();

    @Test
    public void testMaxMessageSize() throws Exception
    {
        StringMessageSink messageSink = new StringMessageSink(coreSession, endpoint.getMethodHandle());
        ByteBuffer utf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x8D, (byte)0x88});

        FutureCallback callback = new FutureCallback();
        coreSession.setMaxTextMessageSize(3);
        messageSink.accept(new Frame(OpCode.TEXT, utf8Payload).setFin(true), callback);

        // Callback should fail and we don't receive the message in the sink.
        RuntimeException error = assertThrows(RuntimeException.class, () -> callback.block(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(MessageTooLargeException.class));
        assertNull(endpoint.messages.poll());
    }

    @Test
    public void testValidUtf8() throws Exception
    {
        StringMessageSink messageSink = new StringMessageSink(coreSession, endpoint.getMethodHandle());
        ByteBuffer utf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x8D, (byte)0x88});

        FutureCallback callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, utf8Payload).setFin(true), callback);
        callback.block(5, TimeUnit.SECONDS);

        assertThat(endpoint.messages.poll(5, TimeUnit.SECONDS), is("\uD800\uDF48")); // UTF-8 encoded payload.
    }

    @Test
    public void testUtf8Continuation() throws Exception
    {
        StringMessageSink messageSink = new StringMessageSink(coreSession, endpoint.getMethodHandle());
        ByteBuffer firstUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90});
        ByteBuffer continuationUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0x8D, (byte)0x88});

        FutureCallback callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, firstUtf8Payload).setFin(false), callback);
        callback.block(5, TimeUnit.SECONDS);

        callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, continuationUtf8Payload).setFin(true), callback);
        callback.block(5, TimeUnit.SECONDS);

        assertThat(endpoint.messages.poll(5, TimeUnit.SECONDS), is("\uD800\uDF48")); // UTF-8 encoded payload.
    }

    @Test
    public void testInvalidSingleFrameUtf8() throws Exception
    {
        StringMessageSink messageSink = new StringMessageSink(coreSession, endpoint.getMethodHandle());
        ByteBuffer invalidUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x8D});

        FutureCallback callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, invalidUtf8Payload).setFin(true), callback);

        // Callback should fail and we don't receive the message in the sink.
        RuntimeException error = assertThrows(RuntimeException.class, () -> callback.block(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(Utf8Appendable.NotUtf8Exception.class));
        assertNull(endpoint.messages.poll());
    }

    @Test
    public void testInvalidMultiFrameUtf8() throws Exception
    {
        StringMessageSink messageSink = new StringMessageSink(coreSession, endpoint.getMethodHandle());
        ByteBuffer firstUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90});
        ByteBuffer continuationUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0x8D});

        FutureCallback firstCallback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, firstUtf8Payload).setFin(false), firstCallback);
        firstCallback.block(5, TimeUnit.SECONDS);

        FutureCallback continuationCallback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, continuationUtf8Payload).setFin(true), continuationCallback);

        // Callback should fail and we don't receive the message in the sink.
        RuntimeException error = assertThrows(RuntimeException.class, () -> continuationCallback.block(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(Utf8Appendable.NotUtf8Exception.class));
        assertNull(endpoint.messages.poll());
    }

    public static class OnMessageEndpoint
    {
        private BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();

        public void onMessage(String message)
        {
            messages.add(message);
        }

        public MethodHandle getMethodHandle() throws Exception
        {
            return MethodHandles.lookup()
                .findVirtual(this.getClass(), "onMessage", MethodType.methodType(void.class, String.class))
                .bindTo(this);
        }
    }
}
