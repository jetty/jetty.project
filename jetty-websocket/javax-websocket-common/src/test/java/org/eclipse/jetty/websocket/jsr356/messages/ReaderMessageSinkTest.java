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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.CompletableFutureCallback;
import org.junit.Test;

public class ReaderMessageSinkTest extends AbstractMessageSinkTest
{
    @Test
    public void testReader_1_Frame() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> copyFuture = new CompletableFuture<>();
        ReaderCopy copy = new ReaderCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Reader.class);
        ReaderMessageSink sink = new ReaderMessageSink(session, copyHandle);
    
        CompletableFutureCallback finCallback = new CompletableFutureCallback();
        sink.accept(new TextFrame().setPayload("Hello World"), finCallback);
        
        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        StringWriter writer = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Writer.contents", writer.getBuffer().toString(), is("Hello World"));
    }
    
    @Test
    public void testReader_3_Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> copyFuture = new CompletableFuture<>();
        ReaderCopy copy = new ReaderCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Reader.class);
        ReaderMessageSink sink = new ReaderMessageSink(session, copyHandle);
        
        CompletableFutureCallback callback1 = new CompletableFutureCallback();
        CompletableFutureCallback callback2 = new CompletableFutureCallback();
        CompletableFutureCallback finCallback = new CompletableFutureCallback();
        
        sink.accept(new TextFrame().setPayload("Hello").setFin(false), callback1);
        sink.accept(new ContinuationFrame().setPayload(", ").setFin(false), callback2);
        sink.accept(new ContinuationFrame().setPayload("World").setFin(true), finCallback);
        
        finCallback.get(1, TimeUnit.SECONDS); // wait for fin callback
        StringWriter writer = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
        assertThat("Writer contents", writer.getBuffer().toString(), is("Hello, World"));
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
