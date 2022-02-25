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

package org.eclipse.jetty.websocket.jakarta.tests.quotes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.EncodeException;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.websocket.jakarta.tests.WSEventTracker;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;

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
            if (logger.isDebugEnabled())
                logger.debug("Writing Quotes: {}", quotes);
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

    private LocalServer server;
    private WebSocketContainer client;

    @BeforeEach
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(EchoQuotesSocket.class);
    }

    @AfterEach
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
        try (FileReader reader = new FileReader(qfile);
             BufferedReader buf = new BufferedReader(reader))
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
    public void testSingleQuotes(TestInfo testInfo) throws Exception
    {
        URI wsUri = server.getTestWsUri(this.getClass(), testInfo.getDisplayName());
        QuotesSocket quoter = new QuotesSocket(testInfo.getTestMethod().toString());

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
    public void testTwoQuotes(TestInfo testInfo) throws Exception
    {
        URI wsUri = server.getTestWsUri(this.getClass(), testInfo.getDisplayName());
        QuotesSocket quoter = new QuotesSocket(testInfo.getTestMethod().toString());

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
