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

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

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

    @ClientEndpoint(decoders = { QuotesDecoder.class })
    public static class QuotesSocket
    {
        public EventQueue<Quotes> messageQueue = new EventQueue<>();
        private CountDownLatch closeLatch = new CountDownLatch(1);

        @OnClose
        public void onClose(CloseReason close)
        {
            closeLatch.countDown();
        }

        @OnMessage
        public synchronized void onMessage(Quotes msg)
        {
            Integer h=hashCode();
            messageQueue.add(msg);
            System.out.printf("%x: Quotes from: %s%n",h,msg.author);
            for (String quote : msg.quotes)
            {
                System.out.printf("%x: - %s%n",h,quote);
            }
        }

        public void awaitClose() throws InterruptedException
        {
            closeLatch.await(4,TimeUnit.SECONDS);
        }
    }

    private static class QuoteServer implements Runnable
    {
        private BlockheadServer server;
        private IBlockheadServerConnection sconnection;
        private CountDownLatch connectLatch = new CountDownLatch(1);

        public QuoteServer(BlockheadServer server)
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

        public void writeQuotes(String filename) throws IOException
        {
            // read file
            File qfile = MavenTestingUtils.getTestResourceFile(filename);
            List<String> lines = new ArrayList<>();
            try (FileReader reader = new FileReader(qfile); BufferedReader buf = new BufferedReader(reader))
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
                sconnection.write(frame);
            }
        }

        public void close() throws IOException
        {
            sconnection.close();
        }

        public void awaitConnect() throws InterruptedException
        {
            connectLatch.await(1,TimeUnit.SECONDS);
        }
    }

    private static final Logger LOG = Log.getLogger(DecoderReaderTest.class);

    @Rule
    public TestTracker tt = new TestTracker();

    private BlockheadServer server;
    private WebSocketContainer client;

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

    // TODO analyse and fix 
    @Ignore
    @Test
    public void testSingleQuotes() throws Exception
    {
        QuotesSocket quoter = new QuotesSocket();
        QuoteServer qserver = new QuoteServer(server);
        new Thread(qserver).start();
        client.connectToServer(quoter,server.getWsUri());
        qserver.awaitConnect();
        qserver.writeQuotes("quotes-ben.txt");
        quoter.messageQueue.awaitEventCount(1,1000,TimeUnit.MILLISECONDS);
        qserver.close();
        quoter.awaitClose();
        Quotes quotes = quoter.messageQueue.poll();
        Assert.assertThat("Quotes Author",quotes.author,is("Benjamin Franklin"));
        Assert.assertThat("Quotes Count",quotes.quotes.size(),is(3));
    }

    // TODO analyse and fix 
    @Test
    @Ignore ("Quotes appear to be able to arrive in any order?")
    public void testTwoQuotes() throws Exception
    {
        QuotesSocket quoter = new QuotesSocket();
        QuoteServer qserver = new QuoteServer(server);
        new Thread(qserver).start();
        client.connectToServer(quoter,server.getWsUri());
        qserver.awaitConnect();
        qserver.writeQuotes("quotes-ben.txt");
        qserver.writeQuotes("quotes-twain.txt");
        quoter.messageQueue.awaitEventCount(2,1000,TimeUnit.MILLISECONDS);
        qserver.close();
        quoter.awaitClose();
        Quotes quotes = quoter.messageQueue.poll();
        Assert.assertThat("Quotes Author",quotes.author,is("Benjamin Franklin"));
        Assert.assertThat("Quotes Count",quotes.quotes.size(),is(3));
    }
}
