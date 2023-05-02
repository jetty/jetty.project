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
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.messages.PartialStringMessageSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PartialStringMessageSinkTest
{
    private CoreSession coreSession = new CoreSession.Empty();
    private OnMessageEndpoint endpoint = new OnMessageEndpoint();
    private PartialStringMessageSink messageSink;

    @BeforeEach
    public void before() throws Exception
    {
        messageSink = new PartialStringMessageSink(coreSession, endpoint.getMethodHandle(), true);
    }

    @Test
    public void testValidUtf8() throws Exception
    {
        ByteBuffer utf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x8D, (byte)0x88});

        FutureCallback callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, utf8Payload).setFin(true), callback);
        callback.block(5, TimeUnit.SECONDS);

        List<String> message = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(message.size(), is(1));
        assertThat(message.get(0), is("\uD800\uDF48")); // UTF-8 encoded payload.
    }

    @Test
    public void testUtf8Continuation() throws Exception
    {
        // GOTHIC LETTER HWAIR
        final String gothicUnicode = "𐍈";
        // Lets use a 4 byte utf-8 sequence
        byte[] utf8Bytes = gothicUnicode.getBytes(StandardCharsets.UTF_8);
        assertThat(utf8Bytes.length, is(4));

        // First payload is 2 bytes, second payload is 2 bytes
        ByteBuffer firstUtf8Payload = BufferUtil.toBuffer(utf8Bytes, 0, 2);
        ByteBuffer continuationUtf8Payload = BufferUtil.toBuffer(utf8Bytes, 2, 2);

        // Check decoding
        Utf8StringBuilder check = new Utf8StringBuilder();
        check.append(utf8Bytes, 0, 2);
        String partial = check.takePartialString(IllegalStateException::new);
        assertThat(partial, equalTo(""));
        check.append(utf8Bytes, 2, 2);
        String complete = check.takeCompleteString(IllegalStateException::new);
        assertThat(complete, equalTo(gothicUnicode));

        FutureCallback callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, firstUtf8Payload).setFin(false), callback);
        callback.block(5, TimeUnit.SECONDS);

        callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, continuationUtf8Payload).setFin(true), callback);
        callback.block(5, TimeUnit.SECONDS);

        List<String> message = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(message.size(), is(2));
        assertThat(message.get(0), is("")); // a not yet complete codepoint
        assertThat(message.get(1), is(gothicUnicode)); // the complete unicode codepoint
    }

    @Test
    public void testInvalidSingleFrameUtf8() throws Exception
    {
        ByteBuffer invalidUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x8D});

        FutureCallback callback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, invalidUtf8Payload).setFin(true), callback);

        // Callback should fail and we don't receive the message in the sink.
        RuntimeException error = assertThrows(RuntimeException.class, () -> callback.block(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(BadPayloadException.class));
        List<String> message = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertTrue(message.isEmpty());
    }

    @Test
    public void testInvalidMultiFrameUtf8() throws Exception
    {
        ByteBuffer firstUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0xF0, (byte)0x90});
        ByteBuffer continuationUtf8Payload = BufferUtil.toBuffer(new byte[]{(byte)0x8D});

        FutureCallback firstCallback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, firstUtf8Payload).setFin(false), firstCallback);
        firstCallback.block(5, TimeUnit.SECONDS);

        FutureCallback continuationCallback = new FutureCallback();
        messageSink.accept(new Frame(OpCode.TEXT, continuationUtf8Payload).setFin(true), continuationCallback);

        // Callback should fail and we only received the first frame which had no full character.
        RuntimeException error = assertThrows(RuntimeException.class, () -> continuationCallback.block(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(BadPayloadException.class));
        List<String> message = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(message.size(), is(1));
        assertThat(message.get(0), is(""));
    }

    public static class OnMessageEndpoint
    {
        private BlockingArrayQueue<List<String>> messages;

        public OnMessageEndpoint()
        {
            messages = new BlockingArrayQueue<>();
            messages.add(new ArrayList<>());
        }

        public void onMessage(String message, boolean last)
        {
            messages.get(messages.size() - 1).add(message);
            if (last)
                messages.add(new ArrayList<>());
        }

        public MethodHandle getMethodHandle() throws Exception
        {
            return MethodHandles.lookup()
                .findVirtual(this.getClass(), "onMessage", MethodType.methodType(void.class, String.class, boolean.class))
                .bindTo(this);
        }
    }
}
