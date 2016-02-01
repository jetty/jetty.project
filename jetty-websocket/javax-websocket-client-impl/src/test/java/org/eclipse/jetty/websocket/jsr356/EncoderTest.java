//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class EncoderTest
{
    private static class EchoServer implements Runnable
    {
        private Thread thread;
        private BlockheadServer server;
        private IBlockheadServerConnection sconnection;
        private CountDownLatch connectLatch = new CountDownLatch(1);

        public EchoServer(BlockheadServer server)
        {
            this.server = server;
        }

        @Override
        public void run()
        {
            try
            {
                sconnection = server.accept();
                sconnection.setSoTimeout(60000);
                sconnection.upgrade();
                sconnection.startEcho();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
            finally
            {
                connectLatch.countDown();
            }
        }

        public void start()
        {
            this.thread = new Thread(this,"EchoServer");
            this.thread.start();
        }

        public void stop()
        {
            if (this.sconnection != null)
            {
                this.sconnection.stopEcho();
                try
                {
                    this.sconnection.close();
                }
                catch (IOException ignore)
                {
                    /* ignore */
                }
            }
        }
    }

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
        private EventQueue<String> messageQueue = new EventQueue<>();

        @Override
        public void onMessage(String message)
        {
            messageQueue.add(message);
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

    @Rule
    public TestTracker tt = new TestTracker();
    private BlockheadServer server;

    private WebSocketContainer client;

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
    }

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSingleQuotes() throws Exception
    {
        EchoServer eserver = new EchoServer(server);
        try
        {
            eserver.start();

            QuotesSocket quoter = new QuotesSocket();

            ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
            List<Class<? extends Encoder>> encoders = new ArrayList<>();
            encoders.add(QuotesEncoder.class);
            builder.encoders(encoders);
            ClientEndpointConfig cec = builder.build();
            client.connectToServer(quoter,cec,server.getWsUri());

            Quotes ben = getQuotes("quotes-ben.txt");
            quoter.write(ben);

            quoter.messageQueue.awaitEventCount(1,1000,TimeUnit.MILLISECONDS);

            String result = quoter.messageQueue.poll();
            assertReceivedQuotes(result,ben);
        }
        finally
        {
            eserver.stop();
        }
    }

    @Test
    public void testTwoQuotes() throws Exception
    {
        EchoServer eserver = new EchoServer(server);
        try
        {
            eserver.start();

            QuotesSocket quoter = new QuotesSocket();
            ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
            List<Class<? extends Encoder>> encoders = new ArrayList<>();
            encoders.add(QuotesEncoder.class);
            builder.encoders(encoders);
            ClientEndpointConfig cec = builder.build();
            client.connectToServer(quoter,cec,server.getWsUri());

            Quotes ben = getQuotes("quotes-ben.txt");
            Quotes twain = getQuotes("quotes-twain.txt");
            quoter.write(ben);
            quoter.write(twain);

            quoter.messageQueue.awaitEventCount(2,1000,TimeUnit.MILLISECONDS);

            String result = quoter.messageQueue.poll();
            assertReceivedQuotes(result,ben);
            result = quoter.messageQueue.poll();
            assertReceivedQuotes(result,twain);
        }
        finally
        {
            eserver.stop();
        }
    }
}
