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

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.messages.ReaderMessageSink;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ReaderMessageSinkTest extends AbstractMessageSinkTest
{
    @Test
    public void testReader1Frame() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> copyFuture = new CompletableFuture<>();
        ReaderCopy copy = new ReaderCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Reader.class);
        ReaderMessageSink sink = new ReaderMessageSink(session.getCoreSession(), copyHandle);

        FutureCallback finCallback = new FutureCallback();
        sink.accept(new Frame(OpCode.TEXT).setPayload("Hello World"), finCallback);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);

        StringWriter writer = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Writer.contents", writer.getBuffer().toString(), is("Hello World"));
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
    }

    @Test
    public void testReader3Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> copyFuture = new CompletableFuture<>();
        ReaderCopy copy = new ReaderCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Reader.class);
        ReaderMessageSink sink = new ReaderMessageSink(session.getCoreSession(), copyHandle);

        FutureCallback callback1 = new FutureCallback();
        FutureCallback callback2 = new FutureCallback();
        FutureCallback finCallback = new FutureCallback();

        sink.accept(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), callback1);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false), callback2);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), finCallback);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);

        StringWriter writer = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Writer contents", writer.getBuffer().toString(), is("Hello, World"));
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
    }

    public static class ReaderCopy implements Consumer<Reader>
    {
        private CompletableFuture<StringWriter> copyFuture;

        public ReaderCopy(CompletableFuture<StringWriter> copyFuture)
        {
            this.copyFuture = copyFuture;
        }

        @Override
        public void accept(Reader reader)
        {
            try
            {
                StringWriter writer = new StringWriter();
                IO.copy(reader, writer);
                copyFuture.complete(writer);
            }
            catch (IOException e)
            {
                copyFuture.completeExceptionally(e);
            }
        }
    }
}
