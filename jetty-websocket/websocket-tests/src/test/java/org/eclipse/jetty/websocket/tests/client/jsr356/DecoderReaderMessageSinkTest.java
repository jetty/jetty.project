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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.CompletableFutureFrameCallback;
import org.eclipse.jetty.websocket.common.io.FutureFrameCallback;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedReaderMessageSink;
import org.junit.AfterClass;
import org.junit.Test;

public class DecoderReaderMessageSinkTest
{
    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 5, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());
    
    @AfterClass
    public static void stopExecutor()
    {
        executor.shutdown();
    }
    
    public static class Lines extends ArrayList<String>
    {
    }
    
    public static class LinesDecoder implements Decoder.TextStream<Lines>
    {
        @Override
        public Lines decode(Reader reader) throws DecodeException, IOException
        {
            Lines lines = new Lines();
            
            try (BufferedReader buf = new BufferedReader(reader))
            {
                String line;
                while ((line = buf.readLine()) != null)
                {
                    lines.add(line);
                }
            }
            
            return lines;
        }
        
        @Override
        public void init(EndpointConfig config)
        {
        }
        
        @Override
        public void destroy()
        {
        }
    }
    
    private WSLocalEndpoint<JsrSession> dummyFunctions = new DummyEndpointFunctions();
    
    @Test
    public void testDecoderReader() throws Exception
    {
        Decoder.TextStream<Lines> decoder = new LinesDecoder();
        
        CompletableFuture<Lines> futureLines = new CompletableFuture<>();
        DecodedReaderMessageSink sink = new DecodedReaderMessageSink(dummyFunctions, executor, decoder, (T) ->
        {
            try
            {
                Lines lines = (Lines) T;
                futureLines.complete(lines);
            }
            catch (Throwable t)
            {
                futureLines.completeExceptionally(t);
            }
            return null;
        });
        
        FutureFrameCallback callback1 = new FutureFrameCallback();
        FutureFrameCallback callback2 = new FutureFrameCallback();
        CompletableFutureFrameCallback finCallback = new CompletableFutureFrameCallback();
        
        sink.accept(new TextFrame().setPayload("Hello.\n").setFin(false), callback1);
        sink.accept(new ContinuationFrame().setPayload("Is this thing on?\n").setFin(false), callback2);
        sink.accept(new ContinuationFrame().setPayload("Please reply\n").setFin(true), finCallback);
        
        finCallback.get(1, TimeUnit.SECONDS); // wait for fin
        Lines lines = futureLines.get(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Lines.size", lines.size(), is(3));
        assertThat("Lines[0]", lines.get(0), is("Hello."));
        assertThat("Lines[1]", lines.get(1), is("Is this thing on?"));
        assertThat("Lines[2]", lines.get(2), is("Please reply"));
    }
}
