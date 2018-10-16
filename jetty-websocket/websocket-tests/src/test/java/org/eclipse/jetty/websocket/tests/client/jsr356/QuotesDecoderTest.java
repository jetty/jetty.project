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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.eclipse.jetty.websocket.tests.jsr356.coders.Quotes;
import org.eclipse.jetty.websocket.tests.jsr356.coders.QuotesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
    
    public static class QuoterServerEndpoint extends UntrustedWSEndpoint
    {
        public QuoterServerEndpoint(String id)
        {
            super(id);
        }
        
        @Override
        public void onWebSocketText(String filename)
        {
            super.onWebSocketText(filename);
            try
            {
                UntrustedWSSession untrustedWSSession = (UntrustedWSSession) session;
                FrameCallback callback = new FrameCallback.Adapter();
                List<WebSocketFrame> frames = QuotesUtil.loadAsWebSocketFrames(filename);
                for (WebSocketFrame frame : frames)
                {
                    untrustedWSSession.getOutgoingHandler().outgoingFrame(frame, callback, BatchMode.OFF);
                }
            }
            catch (Exception e)
            {
                LOG.warn("Unable to send quotes", e);
            }
        }
    }
    
    private static final Logger LOG = Log.getLogger(QuotesDecoderTest.class);
    
    private UntrustedWSServer server;
    private WebSocketContainer client;
    
    @BeforeEach
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }
    
    @BeforeEach
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
        server.start();
    }
    
    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testSingleQuotes(TestInfo testInfo) throws Exception
    {
        server.registerWebSocket("/quoter", new QuoteServingCreator());
        
        URI wsUri = server.getWsUri().resolve("/quoter");
        QuotesSocket clientSocket = new QuotesSocket(testInfo.getDisplayName());
        Session clientSession = client.connectToServer(clientSocket, wsUri);
        
        clientSession.getAsyncRemote().sendText("quotes-ben.txt");
        
        Quotes quotes = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Quotes", quotes, notNullValue());
        assertThat("Quotes Author", quotes.getAuthor(), is("Benjamin Franklin"));
        assertThat("Quotes Count", quotes.getQuotes().size(), is(3));
    }
    
    @Test
    public void testTwoQuotes(TestInfo testInfo) throws Exception
    {
        server.registerWebSocket("/quoter", new QuoteServingCreator());
        
        URI wsUri = server.getWsUri().resolve("/quoter");
        QuotesSocket clientSocket = new QuotesSocket(testInfo.getDisplayName());
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
