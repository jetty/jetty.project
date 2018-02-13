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

package org.eclipse.jetty.websocket.jsr356.tests.quotes;

import static org.junit.Assert.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.EncodeException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.eclipse.jetty.websocket.jsr356.tests.WSEventTracker;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class QuotesEncoderTest
{
    @ClientEndpoint(encoders = QuotesEncoder.class, subprotocols = "echo")
    public static class QuotesSocket extends WSEventTracker
    {
        public QuotesSocket(String id)
        {
            super(id);
        }

        @OnOpen
        public void onOpen(Session session)
        {
            super.onWsOpen(session);
        }

        @OnClose
        public void onClose(CloseReason closeReason)
        {
            super.onWsClose(closeReason);
        }

        @OnError
        public void onError(Throwable cause)
        {
            super.onWsError(cause);
        }

        @OnMessage
        public void onMessage(String message)
        {
            super.onWsText(message);
        }

        public void write(Quotes quotes) throws IOException, EncodeException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Writing Quotes: {}", quotes);
            this.session.getBasicRemote().sendObject(quotes);
        }
    }

    @ServerEndpoint(value = "/test/{testName}/{testMethod}", subprotocols = "echo")
    public static class EchoQuotesSocket
    {
        @OnMessage
        public String onMessage(String msg)
        {
            return msg;
        }
    }

    @Rule
    public TestName testname = new TestName();

    private LocalServer server;
    private WebSocketContainer client;

    @Before
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(EchoQuotesSocket.class);
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertReceivedQuotes(String result, Quotes quotes)
    {
        assertThat("Quote Author", result, Matchers.containsString("Author: " + quotes.getAuthor()));
        for (String quote : quotes.getQuotes())
        {
            assertThat("Quote", result, Matchers.containsString("Quote: " + quote));
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

    private void close(Session session) throws IOException
    {
        if (session != null)
        {
            session.close();
        }
    }

    @Test
    public void testSingleQuotes() throws Exception
    {
        URI wsUri = server.getTestWsUri(this.getClass(), testname);
        QuotesSocket quoter = new QuotesSocket(testname.getMethodName());

        Session session = null;
        try
        {
            session = client.connectToServer(quoter, wsUri);

            Quotes ben = getQuotes("quotes-ben.txt");
            quoter.write(ben);

            String incomingMessage = quoter.messageQueue.poll(5, TimeUnit.SECONDS);
            assertReceivedQuotes(incomingMessage, ben);
        }
        finally
        {
            close(session);
        }
    }

    @Test
    public void testTwoQuotes() throws Exception
    {
        URI wsUri = server.getTestWsUri(this.getClass(), testname);
        QuotesSocket quoter = new QuotesSocket(testname.getMethodName());

        Session session = null;
        try
        {
            session = client.connectToServer(quoter, wsUri);

            Quotes ben = getQuotes("quotes-ben.txt");
            Quotes twain = getQuotes("quotes-twain.txt");
            quoter.write(ben);
            quoter.write(twain);

            String incomingQuote;

            incomingQuote = quoter.messageQueue.poll(5, TimeUnit.SECONDS);
            assertReceivedQuotes(incomingQuote, ben);

            incomingQuote = quoter.messageQueue.poll(5, TimeUnit.SECONDS);
            assertReceivedQuotes(incomingQuote, twain);
        }
        finally
        {
            close(session);
        }
    }
}
