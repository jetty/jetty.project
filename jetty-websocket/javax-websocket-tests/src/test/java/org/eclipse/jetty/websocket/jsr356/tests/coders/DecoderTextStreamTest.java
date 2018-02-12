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

package org.eclipse.jetty.websocket.jsr356.tests.coders;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.websocket.Decoder;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.jsr356.CompletableFutureCallback;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.tests.FunctionMethod;
import org.eclipse.jetty.websocket.jsr356.tests.client.AbstractClientSessionTest;
import org.junit.Test;

/**
 * Test various {@link javax.websocket.Decoder.TextStream} scenarios
 */
public class DecoderTextStreamTest extends AbstractClientSessionTest
{
    @Test
    public void testQuotes_Decoder_Direct() throws Exception
    {
        Decoder.TextStream<Quotes> decoder = new QuotesDecoder();

        Path quotesPath = MavenTestingUtils.getTestResourcePath("quotes-ben.txt");
        try (Reader reader = Files.newBufferedReader(quotesPath))
        {
            Quotes quotes = decoder.decode(reader);
            assertThat("Decoded Quotes", quotes, notNullValue());
            assertThat("Decoded Quotes.author", quotes.getAuthor(), is("Benjamin Franklin"));
            assertThat("Decoded Quotes.quotes.size", quotes.getQuotes().size(), is(3));
        }
    }

    @Test
    public void testQuotes_DecodedReaderMessageSink() throws Exception
    {
        Decoder.TextStream<Quotes> decoder = new QuotesDecoder();
        CompletableFuture<Quotes> futureQuotes = new CompletableFuture<>();
        MethodHandle functionHandle = FunctionMethod.getFunctionApplyMethodHandle();
        MethodHandle quoteHandle = functionHandle.bindTo((Function<Quotes, Void>) (quotes) ->
        {
            try
            {
                futureQuotes.complete(quotes);
            }
            catch (Throwable t)
            {
                futureQuotes.completeExceptionally(t);
            }
            return null;
        });


        DecodedTextStreamMessageSink sink = new DecodedTextStreamMessageSink(session, decoder, quoteHandle);

        List<CompletableFutureCallback> callbacks = new ArrayList<>();
        CompletableFutureCallback finCallback = null;
        List<WebSocketFrame> frames = QuotesUtil.loadAsWebSocketFrames("quotes-ben.txt");
        for (WebSocketFrame frame : frames)
        {
            CompletableFutureCallback callback = new CompletableFutureCallback();
            if (frame.isFin())
            {
                finCallback = callback;
            }
            callbacks.add(callback);
            sink.accept(frame, callback);
        }

        assertThat("Should have found finCallback", finCallback, notNullValue());
        finCallback.get(1, TimeUnit.SECONDS); // wait for fin
        Quotes quotes = futureQuotes.get(1, TimeUnit.SECONDS);
        assertThat("Quotes", quotes, notNullValue());
        for (CompletableFutureCallback callback : callbacks)
        {
            assertThat("Callback", callback.isDone(), is(true));
        }
        assertThat("Quotes.author", quotes.getAuthor(), is("Benjamin Franklin"));
        assertThat("Quotes.count", quotes.getQuotes().size(), is(3));
    }
}
