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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.coders;

import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.ee9.websocket.jakarta.common.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.FunctionMethod;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.client.AbstractClientSessionTest;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test various {@link jakarta.websocket.Decoder.TextStream} scenarios
 */
public class DecoderTextStreamTest extends AbstractClientSessionTest
{
    private final WebSocketComponents _components = new WebSocketComponents();

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

        List<RegisteredDecoder> decoders = toRegisteredDecoderList(QuotesDecoder.class, Quotes.class);
        DecodedTextStreamMessageSink<Quotes> sink = new DecodedTextStreamMessageSink<>(session.getCoreSession(), quoteHandle, decoders);

        List<FutureCallback> callbacks = new ArrayList<>();
        FutureCallback finCallback = null;
        List<Frame> frames = QuotesUtil.loadAsWebSocketFrames("quotes-ben.txt");
        for (Frame frame : frames)
        {
            FutureCallback callback = new FutureCallback();
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
        for (FutureCallback callback : callbacks)
        {
            assertThat("Callback", callback.isDone(), is(true));
        }
        assertThat("Quotes.author", quotes.getAuthor(), is("Benjamin Franklin"));
        assertThat("Quotes.count", quotes.getQuotes().size(), is(3));
    }

    public List<RegisteredDecoder> toRegisteredDecoderList(Class<? extends Decoder> clazz, Class<?> objectType)
    {
        Class<? extends Decoder> interfaceType;
        if (Decoder.Text.class.isAssignableFrom(clazz))
            interfaceType = Decoder.Text.class;
        else if (Decoder.Binary.class.isAssignableFrom(clazz))
            interfaceType = Decoder.Binary.class;
        else if (Decoder.TextStream.class.isAssignableFrom(clazz))
            interfaceType = Decoder.TextStream.class;
        else if (Decoder.BinaryStream.class.isAssignableFrom(clazz))
            interfaceType = Decoder.BinaryStream.class;
        else
            throw new IllegalStateException();

        return List.of(new RegisteredDecoder(clazz, interfaceType, objectType, ClientEndpointConfig.Builder.create().build(), _components));
    }
}
