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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.containsString;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class EncoderTest
{
    public static class Quotes
    {
        private String author;
        private List<String> quotes = new ArrayList<>();

        public void addQuote(String quote)
        {
            quotes.add(quote);
        }

        public String getAuthor()
        {
            return author;
        }

        public List<String> getQuotes()
        {
            return quotes;
        }

        public void setAuthor(String author)
        {
            this.author = author;
        }
    }

    public static class QuotesEncoder implements Encoder.Text<Quotes>
    {
        @Override
        public void destroy()
        {
        }

        @Override
        public String encode(Quotes q) throws EncodeException
        {
            StringBuilder buf = new StringBuilder();
            buf.append("Author: ").append(q.getAuthor());
            buf.append(System.lineSeparator());
            for (String quote : q.quotes)
            {
                buf.append("Quote: ").append(quote);
                buf.append(System.lineSeparator());
            }
            return buf.toString();
        }

        @Override
        public void init(EndpointConfig config)
        {
        }
    }

    public static class QuotesSocket extends Endpoint implements MessageHandler.Whole<String>
    {
        private Session session;
        private LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();

        @Override
        public void onMessage(String message)
        {
            messageQueue.offer(message);
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            this.session.addMessageHandler(this);
        }

        public void write(Quotes quotes) throws IOException, EncodeException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Writing Quotes: {}",quotes);
            this.session.getBasicRemote().sendObject(quotes);
        }
    }

    private static final Logger LOG = Log.getLogger(EncoderTest.class);

    private static BlockheadServer server;
    private WebSocketContainer client;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertReceivedQuotes(String result, Quotes quotes)
    {
        Assert.assertThat("Quote Author",result,containsString("Author: " + quotes.getAuthor()));
        for (String quote : quotes.quotes)
        {
            Assert.assertThat("Quote",result,containsString("Quote: " + quote));
        }
    }

    private Quotes getQuotes(String filename) throws IOException
    {
        Quotes quotes = new Quotes();

        // read file
        File qfile = MavenTestingUtils.getTestResourceFile(filename);
        try (FileReader reader = new FileReader(qfile); BufferedReader buf = new BufferedReader(reader))
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

    @Before
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
        client.setDefaultMaxSessionIdleTimeout(10000);
    }

    @After
    public void stopClient() throws Exception
    {
        ((LifeCycle)client).stop();
    }

    @Test(timeout = 10000)
    public void testSingleQuotes() throws Exception
    {
        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        QuotesSocket quoter = new QuotesSocket();

        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        List<Class<? extends Encoder>> encoders = new ArrayList<>();
        encoders.add(QuotesEncoder.class);
        builder.encoders(encoders);
        ClientEndpointConfig cec = builder.build();
        client.connectToServer(quoter,cec,server.getWsUri());

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Setup echo of frames on server side
            serverConn.setIncomingFrameConsumer(new DataFrameEcho(serverConn));

            Quotes ben = getQuotes("quotes-ben.txt");
            quoter.write(ben);

            String result = quoter.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertReceivedQuotes(result,ben);
        }
    }

    @Test(timeout = 10000)
    public void testTwoQuotes() throws Exception
    {
        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        QuotesSocket quoter = new QuotesSocket();
        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        List<Class<? extends Encoder>> encoders = new ArrayList<>();
        encoders.add(QuotesEncoder.class);
        builder.encoders(encoders);
        ClientEndpointConfig cec = builder.build();
        client.connectToServer(quoter,cec,server.getWsUri());

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Setup echo of frames on server side
            serverConn.setIncomingFrameConsumer(new DataFrameEcho(serverConn));

            Quotes ben = getQuotes("quotes-ben.txt");
            Quotes twain = getQuotes("quotes-twain.txt");
            quoter.write(ben);
            quoter.write(twain);

            String result = quoter.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertReceivedQuotes(result,ben);
            result = quoter.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertReceivedQuotes(result,twain);
        }
    }

    private static class DataFrameEcho implements Consumer<Frame>
    {
        private final BlockheadConnection connection;

        public DataFrameEcho(BlockheadConnection connection)
        {
            this.connection = connection;
        }

        @Override
        public void accept(Frame frame)
        {
            if (OpCode.isDataFrame(frame.getOpCode()))
            {
                WebSocketFrame copy = WebSocketFrame.copy(frame);
                copy.setMask(null); // remove client masking
                connection.write(copy);
            }
        }
    }
}
