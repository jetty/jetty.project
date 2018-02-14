//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.messages;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

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
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.jsr356.CompletableFutureCallback;
import org.junit.Test;

public class InputStreamMessageSinkTest extends AbstractMessageSinkTest
{
    @Test
    public void testInputStream_1_Message_1_Frame() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(session, copyHandle);

        CompletableFutureCallback finCallback = new CompletableFutureCallback();
        ByteBuffer data = BufferUtil.toBuffer("Hello World", UTF_8);
        sink.accept(new BinaryFrame().setPayload(data), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Hello World"));
    }

    @Test
    public void testInputStream_2_Messages_2_Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(session, copyHandle);

        CompletableFutureCallback fin1Callback = new CompletableFutureCallback();
        ByteBuffer data1 = BufferUtil.toBuffer("Hello World", UTF_8);
        sink.accept(new BinaryFrame().setPayload(data1).setFin(true), fin1Callback);

        fin1Callback.get(1, TimeUnit.SECONDS); // wait for callback (can't sent next message until this callback finishes)
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", fin1Callback.isDone(), is(true));
        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Hello World"));

        CompletableFutureCallback fin2Callback = new CompletableFutureCallback();
        ByteBuffer data2 = BufferUtil.toBuffer("Greetings Earthling", UTF_8);
        sink.accept(new BinaryFrame().setPayload(data2).setFin(true), fin2Callback);

        fin2Callback.get(1, TimeUnit.SECONDS); // wait for callback
        byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", fin2Callback.isDone(), is(true));
        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Greetings Earthling"));
    }

    @Test
    public void testInputStream_1_Message_3_Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(session, copyHandle);

        CompletableFutureCallback callback1 = new CompletableFutureCallback();
        CompletableFutureCallback callback2 = new CompletableFutureCallback();
        CompletableFutureCallback finCallback = new CompletableFutureCallback();

        sink.accept(new BinaryFrame().setPayload("Hello").setFin(false), callback1);
        sink.accept(new ContinuationFrame().setPayload(", ").setFin(false), callback2);
        sink.accept(new ContinuationFrame().setPayload("World").setFin(true), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        ByteArrayOutputStream byteStream = copy.poll(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));

        assertThat("Writer.contents", new String(byteStream.toByteArray(), UTF_8), is("Hello, World"));
    }

    @Test
    public void testInputStream_1_Message_4_Frames_Empty_Fin() throws InterruptedException, ExecutionException, TimeoutException
    {
        InputStreamCopy copy = new InputStreamCopy();
        MethodHandle copyHandle = getAcceptHandle(copy, InputStream.class);
        InputStreamMessageSink sink = new InputStreamMessageSink(session, copyHandle);

        CompletableFutureCallback callback1 = new CompletableFutureCallback();
        CompletableFutureCallback callback2 = new CompletableFutureCallback();
        CompletableFutureCallback callback3 = new CompletableFutureCallback();
        CompletableFutureCallback finCallback = new CompletableFutureCallback();

        sink.accept(new BinaryFrame().setPayload("Greetings").setFin(false), callback1);
        sink.accept(new ContinuationFrame().setPayload(", ").setFin(false), callback2);
        sink.accept(new ContinuationFrame().setPayload("Earthling").setFin(false), callback3);
        sink.accept(new ContinuationFrame().setPayload(new byte[0]).setFin(true), finCallback);

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
