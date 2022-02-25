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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.messages.MessageReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageReaderTest
{
    private final MessageReader reader = new MessageReader();
    private final CompletableFuture<String> message = new CompletableFuture<>();
    private boolean first = true;

    @BeforeEach
    public void before()
    {
        // Read the message in a different thread.
        new Thread(() ->
        {
            try
            {
                message.complete(IO.toString(reader));
            }
            catch (IOException e)
            {
                message.completeExceptionally(e);
            }
        }).start();
    }

    @Test
    public void testSingleFrameMessage() throws Exception
    {
        giveString("hello world!", true);

        String s = message.get(5, TimeUnit.SECONDS);
        assertThat(s, is("hello world!"));
    }

    @Test
    public void testFragmentedMessage() throws Exception
    {
        giveString("hello", false);
        giveString(" ", false);
        giveString("world", false);
        giveString("!", true);

        String s = message.get(5, TimeUnit.SECONDS);
        assertThat(s, is("hello world!"));
    }

    @Test
    public void testEmptySegments() throws Exception
    {
        giveString("", false);
        giveString("hello ", false);
        giveString("", false);
        giveString("", false);
        giveString("world!", false);
        giveString("", false);
        giveString("", true);

        String s = message.get(5, TimeUnit.SECONDS);
        assertThat(s, is("hello world!"));
    }

    @Test
    public void testCloseStream() throws Exception
    {
        giveString("hello ", false);
        reader.close();
        giveString("world!", true);

        ExecutionException error = assertThrows(ExecutionException.class, () -> message.get(5, TimeUnit.SECONDS));
        Throwable cause = error.getCause();
        assertThat(cause, instanceOf(IOException.class));
        assertThat(cause.getMessage(), is("Closed"));
    }

    @Test
    public void testInvalidUtf8() throws Exception
    {
        ByteBuffer invalidUtf8Payload = BufferUtil.toBuffer(new byte[]{0x7F, (byte)0xFF, (byte)0xFF});
        giveByteBuffer(invalidUtf8Payload, true);

        ExecutionException error = assertThrows(ExecutionException.class, () -> message.get(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(MalformedInputException.class));
    }

    private void giveString(String s, boolean last) throws IOException
    {
        giveByteBuffer(ByteBuffer.wrap(StringUtil.getUtf8Bytes(s)), last);
    }

    private void giveByteBuffer(ByteBuffer buffer, boolean last) throws IOException
    {
        byte opCode = first ? OpCode.TEXT : OpCode.CONTINUATION;
        Frame frame = new Frame(opCode, last, buffer);
        FutureCallback callback = new FutureCallback();
        reader.accept(frame, callback);
        callback.block(5, TimeUnit.SECONDS);
        first = false;
    }
}
