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

package org.eclipse.jetty.websocket.javax.tests.coders;

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
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.javax.common.CompletableFutureCallback;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.websocket.javax.tests.FunctionMethod;
import org.eclipse.jetty.websocket.javax.tests.client.AbstractClientSessionTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test various {@link javax.websocket.Decoder.TextStream} scenarios
 */
public class DecoderTextStreamTest extends AbstractClientSessionTest
{
    @Test
    public void testQuotesDecoderDirect() throws Exception
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
    public void testQuotesDecodedReaderMessageSink() throws Exception
    {
        Decoder.TextStream<Quotes> decoder = new QuotesDecoder();
        CompletableFuture<Quotes> futureQuotes = new CompletableFuture<>();
        MethodHandle functionHandle = FunctionMethod.getFunctionApplyMethodHandle();
        MethodHandle quoteHandle = functionHandle.bindTo((Function<Quotes, Void>)(quotes) ->
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
        List<Frame> frames = QuotesUtil.loadAsWebSocketFrames("quotes-ben.txt");
        for (Frame frame : frames)
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
