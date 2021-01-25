//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DecoderReaderTest
{
    public static class Quotes
    {
        private String author;
        private List<String> quotes = new ArrayList<>();

        public String getAuthor()
        {
            return author;
        }

        public void setAuthor(String author)
        {
            this.author = author;
        }

        public List<String> getQuotes()
        {
            return quotes;
        }

        public void addQuote(String quote)
        {
            quotes.add(quote);
        }
    }

    public static class QuotesDecoder implements Decoder.TextStream<Quotes>
    {
        @Override
        public void init(EndpointConfig config)
        {
        }

        @Override
        public void destroy()
        {
        }

        @Override
        public Quotes decode(Reader reader) throws DecodeException, IOException
        {
            Quotes quotes = new Quotes();
            try (BufferedReader buf = new BufferedReader(reader))
            {
                String line;
                while ((line = buf.readLine()) != null)
                {
                    switch (line.charAt(0))
                    {
                        case 'a':
                            quotes.setAuthor(line.substring(2));
                            break;
                        case 'q':
                            quotes.addQuote(line.substring(2));
                            break;
                    }
                }
            }
            return quotes;
        }
    }

    @ClientEndpoint(decoders = {QuotesDecoder.class})
    public static class QuotesSocket
    {
        private static final Logger LOG = Log.getLogger(QuotesSocket.class);
        public LinkedBlockingQueue<Quotes> messageQueue = new LinkedBlockingQueue<>();
        private CountDownLatch closeLatch = new CountDownLatch(1);

        @OnClose
        public void onClose(CloseReason close)
        {
            closeLatch.countDown();
        }

        @OnMessage
        public synchronized void onMessage(Quotes msg)
        {
            messageQueue.offer(msg);
            if (LOG.isDebugEnabled())
            {
                String hashcode = Integer.toHexString(Objects.hashCode(this));
                LOG.debug("{}: Quotes from: {}", hashcode, msg.author);
                for (String quote : msg.quotes)
                {
                    LOG.debug("{}: - {}", hashcode, quote);
                }
            }
        }

        public void awaitClose() throws InterruptedException
        {
            closeLatch.await(4, TimeUnit.SECONDS);
        }
    }

    private static BlockheadServer server;
    private WebSocketContainer client;

    @BeforeEach
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        ((LifeCycle)client).stop();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSingleQuotes() throws Exception
    {
        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        QuotesSocket quoter = new QuotesSocket();
        client.connectToServer(quoter, server.getWsUri());

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            writeQuotes(serverConn, "quotes-ben.txt");

            Quotes quotes = quoter.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Quotes Author", quotes.author, is("Benjamin Franklin"));
            assertThat("Quotes Count", quotes.quotes.size(), is(3));
        }
    }

    /**
     * Test that multiple quotes can go through decoder without issue.
     * <p>
     * Since this decoder is Reader based, this is a useful test to ensure
     * that the Reader creation / dispatch / hand off to the user endpoint
     * works properly.
     * </p>
     */
    @Test
    public void testTwoQuotes() throws Exception
    {
        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        QuotesSocket quoter = new QuotesSocket();
        client.connectToServer(quoter, server.getWsUri());

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            writeQuotes(serverConn, "quotes-ben.txt");
            Quotes quotes = quoter.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Quotes Author", quotes.author, is("Benjamin Franklin"));
            assertThat("Quotes Count", quotes.quotes.size(), is(3));

            writeQuotes(serverConn, "quotes-twain.txt");
            quotes = quoter.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Quotes Author", quotes.author, is("Mark Twain"));
        }
    }

    private void writeQuotes(BlockheadConnection conn, String filename) throws IOException
    {
        // read file
        File qfile = MavenTestingUtils.getTestResourceFile(filename);
        List<String> lines = new ArrayList<>();
        try (FileReader reader = new FileReader(qfile);
             BufferedReader buf = new BufferedReader(reader))
        {
            String line;
            while ((line = buf.readLine()) != null)
            {
                lines.add(line);
            }
        }
        // write file out, each line on a separate frame, but as
        // 1 whole message
        for (int i = 0; i < lines.size(); i++)
        {
            WebSocketFrame frame;
            if (i == 0)
            {
                frame = new TextFrame();
            }
            else
            {
                frame = new ContinuationFrame();
            }
            frame.setFin((i >= (lines.size() - 1)));
            frame.setPayload(BufferUtil.toBuffer(lines.get(i) + "\n"));
            conn.write(frame);
        }
    }
}
