//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.FutureFrameCallback;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.junit.AfterClass;
import org.junit.Test;

public class ReaderMessageSinkTest
{
    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 5, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());
    
    @AfterClass
    public static void stopExecutor()
    {
        executor.shutdown();
    }
    
    @Test
    public void testReader_SingleFrame() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> futureWriter = new CompletableFuture<>();
        ReaderMessageSink sink = new ReaderMessageSink(executor, new ReaderCopy(futureWriter));
        
        FutureFrameCallback callback1 = new FutureFrameCallback();
        sink.accept(new TextFrame().setPayload("Hello World"), callback1);
        
        StringWriter writer = futureWriter.get(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Writer.contents", writer.getBuffer().toString(), is("Hello World"));
    }
    
    @Test
    public void testReader_MultiFrame() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> futureWriter = new CompletableFuture<>();
        ReaderMessageSink sink = new ReaderMessageSink(executor, new ReaderCopy(futureWriter));
        
        FutureFrameCallback callback1 = new FutureFrameCallback();
        FutureFrameCallback callback2 = new FutureFrameCallback();
        FutureFrameCallback callback3 = new FutureFrameCallback();
        
        sink.accept(new TextFrame().setPayload("Hello").setFin(false), callback1);
        sink.accept(new ContinuationFrame().setPayload(", ").setFin(false), callback2);
        sink.accept(new ContinuationFrame().setPayload("World").setFin(true), callback3);
        
        StringWriter writer = futureWriter.get(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("Callback3.done", callback3.isDone(), is(true));
        assertThat("Writer contents", writer.getBuffer().toString(), is("Hello, World"));
    }
    
    private class ReaderCopy implements Function<Reader, Void>
    {
        private CompletableFuture<StringWriter> futureWriter;
        
        public ReaderCopy(CompletableFuture<StringWriter> futureWriter)
        {
            this.futureWriter = futureWriter;
        }
        
        @Override
        public Void apply(Reader reader)
        {
            try
            {
                StringWriter writer = new StringWriter();
                IO.copy(reader, writer);
                futureWriter.complete(writer);
            }
            catch (IOException e)
            {
                futureWriter.completeExceptionally(e);
            }
            return null;
        }
    }
}
