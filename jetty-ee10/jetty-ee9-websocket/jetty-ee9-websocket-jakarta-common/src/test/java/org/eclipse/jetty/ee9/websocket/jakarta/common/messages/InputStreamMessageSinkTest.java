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

package org.eclipse.jetty.ee9.websocket.jakarta.common.messages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.ee9.websocket.jakarta.common.AbstractSessionTest;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.messages.InputStreamMessageSink;
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

        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        finCallback.get(1, TimeUnit.SECONDS);
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Writer.contents", byteStream.toString(UTF_8), is("Hello World"));
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
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

        // wait for demand (can't sent next message until a new frame is demanded)
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        fin1Callback.get(1, TimeUnit.SECONDS);
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Writer.contents", byteStream.toString(UTF_8), is("Hello World"));
        assertThat("FinCallback.done", fin1Callback.isDone(), is(true));

        FutureCallback fin2Callback = new FutureCallback();
        ByteBuffer data2 = BufferUtil.toBuffer("Greetings Earthling", UTF_8);
        sink.accept(new Frame(OpCode.BINARY).setPayload(data2).setFin(true), fin2Callback);

        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        fin2Callback.get(1, TimeUnit.SECONDS);
        byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Writer.contents", byteStream.toString(UTF_8), is("Greetings Earthling"));
        assertThat("FinCallback.done", fin2Callback.isDone(), is(true));
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
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false), callback2);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), finCallback);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);

        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Writer.contents", byteStream.toString(UTF_8), is("Hello, World"));
        assertThat("callback1.done", callback1.isDone(), is(true));
        assertThat("callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
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
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false), callback2);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload("Earthling").setFin(false), callback3);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(new byte[0]).setFin(true), finCallback);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);

        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Writer.contents", byteStream.toString(UTF_8), is("Greetings, Earthling"));
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("Callback3.done", callback3.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
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

        public ByteArrayOutputStream poll(long time, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            return Objects.requireNonNull(streams.poll(time, unit)).get(time, unit);
        }
    }
}
