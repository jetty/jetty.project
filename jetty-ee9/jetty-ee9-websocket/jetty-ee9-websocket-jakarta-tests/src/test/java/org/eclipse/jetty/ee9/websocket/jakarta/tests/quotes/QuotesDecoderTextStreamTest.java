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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.quotes;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Tests a {@link jakarta.websocket.Decoder.TextStream} automatic decoding to a Socket onMessage parameter
 */
public class QuotesDecoderTextStreamTest
{
    @ServerEndpoint(value = "/quotes/echo/string", decoders = QuotesDecoder.class)
    public static class QuotesEchoStringSocket
    {
        @SuppressWarnings("unused")
        @OnMessage
        public String onQuotes(Quotes q)
        {
            StringBuilder buf = new StringBuilder();
            buf.append("Author: ").append(q.getAuthor()).append('\n');
            for (String quote : q.getQuotes())
            {
                buf.append("Quote: ").append(quote).append('\n');
            }
            return buf.toString();
        }
    }

    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(QuotesEchoStringSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testQuoteEchoStringBulk() throws Exception
    {
        List<Frame> send = QuotesUtil.loadAsWebSocketFrames("quotes-ben.txt");
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer("/quotes/echo/string"))
        {
            session.sendBulk(send);

            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), allOf(
                containsString("Author: Benjamin Franklin"),
                containsString("Quote: Our new Constitution is now established")
            ));
        }
    }

    @Test
    public void testQuoteEchoStringSmallSegments() throws Exception
    {
        List<Frame> send = QuotesUtil.loadAsWebSocketFrames("quotes-ben.txt");
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer("/quotes/echo/string"))
        {
            session.sendSegmented(send, 3);

            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), allOf(
                containsString("Author: Benjamin Franklin"),
                containsString("Quote: Our new Constitution is now established")
            ));
        }
    }

    @Test
    public void testQuoteEchoStringFrameWise() throws Exception
    {
        List<Frame> send = QuotesUtil.loadAsWebSocketFrames("quotes-ben.txt");
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer("/quotes/echo/string"))
        {
            session.sendFrames(send);

            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), allOf(
                containsString("Author: Benjamin Franklin"),
                containsString("Quote: Our new Constitution is now established")
            ));
        }
    }
}
