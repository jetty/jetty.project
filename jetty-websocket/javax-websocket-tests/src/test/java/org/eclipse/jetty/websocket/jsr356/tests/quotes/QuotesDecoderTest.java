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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.eclipse.jetty.websocket.jsr356.tests.WSEventTracker;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class QuotesDecoderTest
{
    public static class QuoteServingCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            QuoterServerEndpoint endpoint = new QuoterServerEndpoint(WebSocketBehavior.SERVER.name());
            resp.setAcceptedSubProtocol("quotes");
            return endpoint;
        }
    }

    @ServerEndpoint(value = "/quoter")
    public static class QuoterServerEndpoint extends WSEventTracker
    {
        public QuoterServerEndpoint(String id)
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

        @OnMessage
        public void onOpenResource(String filename)
        {
            super.onWsText(filename);
            try
            {
                RemoteEndpoint.Basic remote = session.getBasicRemote();
                List<String> lines = QuotesUtil.loadLines(filename);
                for (String line : lines)
                {
                    remote.sendText(line + "\n", false);
                }
                remote.sendText("", true);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to send quotes", e);
            }
        }
    }

    private static final Logger LOG = Log.getLogger(QuotesDecoderTest.class);

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
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSingleQuotes() throws Exception
    {
        server.registerWebSocket("/quoter", new QuoteServingCreator());

        URI wsUri = server.getWsUri().resolve("/quoter");
        QuotesSocket clientSocket = new QuotesSocket(testname.getMethodName());
        Session clientSession = client.connectToServer(clientSocket, wsUri);

        clientSession.getAsyncRemote().sendText("quotes-ben.txt");

        Quotes quotes = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Quotes", quotes, notNullValue());
        assertThat("Quotes Author", quotes.getAuthor(), is("Benjamin Franklin"));
        assertThat("Quotes Count", quotes.getQuotes().size(), is(3));
    }

    @Test
    public void testTwoQuotes() throws Exception
    {
        server.registerWebSocket("/quoter", new QuoteServingCreator());

        URI wsUri = server.getWsUri().resolve("/quoter");
        QuotesSocket clientSocket = new QuotesSocket(testname.getMethodName());
        Session clientSession = client.connectToServer(clientSocket, wsUri);

        clientSession.getAsyncRemote().sendText("quotes-ben.txt");
        clientSession.getAsyncRemote().sendText("quotes-twain.txt");

        Quotes quotes;
        quotes = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Quotes", quotes, notNullValue());
        assertThat("Quotes Author", quotes.getAuthor(), is("Benjamin Franklin"));
        assertThat("Quotes Count", quotes.getQuotes().size(), is(3));

        quotes = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Quotes", quotes, notNullValue());
        assertThat("Quotes Author", quotes.getAuthor(), is("Mark Twain"));
        assertThat("Quotes Count", quotes.getQuotes().size(), is(4));
    }
}
