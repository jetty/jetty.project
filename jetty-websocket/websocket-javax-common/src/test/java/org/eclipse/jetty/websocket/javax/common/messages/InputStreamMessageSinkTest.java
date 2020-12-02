//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.AbstractSessionTest;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class InputStreamMessageSinkTest extends AbstractMessageSinkTest
{
    @Test
    public void testInputStream1Message1Frame() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(AbstractSessionTest.session.getCoreSession(), copyHandle);

        FutureCallback finCallback = new FutureCallback();
        ByteBuffer data = BufferUtil.toBuffer("Hello World", UTF_8);
        sink.accept(new Frame(OpCode.BINARY).setPayload(data), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Hello World"));
    }

    @Test
    public void testInputStream2Messages2Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(AbstractSessionTest.session.getCoreSession(), copyHandle);

        FutureCallback fin1Callback = new FutureCallback();
        ByteBuffer data1 = BufferUtil.toBuffer("Hello World", UTF_8);
        sink.accept(new Frame(OpCode.BINARY).setPayload(data1).setFin(true), fin1Callback);

        fin1Callback.get(1, TimeUnit.SECONDS); // wait for callback (can't sent next message until this callback finishes)
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", fin1Callback.isDone(), is(true));
        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Hello World"));

        FutureCallback fin2Callback = new FutureCallback();
        ByteBuffer data2 = BufferUtil.toBuffer("Greetings Earthling", UTF_8);
        sink.accept(new Frame(OpCode.BINARY).setPayload(data2).setFin(true), fin2Callback);

        fin2Callback.get(1, TimeUnit.SECONDS); // wait for callback
        byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", fin2Callback.isDone(), is(true));
        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Greetings Earthling"));
    }

    @Test
    public void testInputStream1Message3Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(AbstractSessionTest.session.getCoreSession(), copyHandle);

        FutureCallback callback1 = new FutureCallback();
        FutureCallback callback2 = new FutureCallback();
        FutureCallback finCallback = new FutureCallback();

        sink.accept(new Frame(OpCode.BINARY).setPayload("Hello").setFin(false), callback1);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false), callback2);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));

        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Hello, World"));
    }

    @Test
    public void testInputStream1Message4FramesEmptyFin() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(AbstractSessionTest.session.getCoreSession(), copyHandle);

        FutureCallback callback1 = new FutureCallback();
        FutureCallback callback2 = new FutureCallback();
        FutureCallback callback3 = new FutureCallback();
        FutureCallback finCallback = new FutureCallback();

        sink.accept(new Frame(OpCode.BINARY).setPayload("Greetings").setFin(false), callback1);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false), callback2);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload("Earthling").setFin(false), callback3);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(new byte[0]).setFin(true), finCallback);

        finCallback.get(5, TimeUnit.SECONDS); // wait for callback
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("Callback3.done", callback3.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));

        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Greetings, Earthling"));
    }

    public static class InputStreamCopy implements Consumer<InputStream>
    {
        private final BlockingArrayQueue<CompletableFuture<ByteArrayOutputStream>> streams = new BlockingArrayQueue<>();

        @Override
        public void accept(InputStream in)
        {
            CompletableFuture<ByteArrayOutputStream> entry = new CompletableFuture<>();
            try
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                IO.copy(in, out);
                entry.complete(out);
                streams.offer(entry);
            }
            catch (IOException e)
            {
                entry.completeExceptionally(e);
                streams.offer(entry);
            }
        }

        public ByteArrayOutputStream poll(long time, TimeUnit unit) throws InterruptedException, ExecutionException
        {
            return streams.poll(time, unit).get();
        }
    }
}
