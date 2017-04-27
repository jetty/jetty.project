//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.tests.AbstractJsrTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSConnection;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class QuotesDecoderTest
{
    @ClientEndpoint(decoders = QuotesDecoder.class, subprotocols = "quotes")
    public static class QuotesSocket extends AbstractJsrTrackingEndpoint
    {
        public BlockingQueue<Quotes> messageQueue = new LinkedBlockingDeque<>();
        
        public QuotesSocket(String id)
        {
            super(id);
        }
        
        @SuppressWarnings("unused")
        @OnMessage
        public void onMessage(Quotes quote)
        {
            System.err.printf("QuotesSocket.onMessage(%s)%n",quote);
            messageQueue.offer(quote);
        }
    }
    
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
                UntrustedWSConnection untrustedWSConnection = untrustedWSSession.getUntrustedConnection();
                writeQuotes(filename, untrustedWSConnection);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        
        public void writeQuotes(String filename, UntrustedWSConnection connection) throws Exception
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
                connection.write(frame);
            }
        }
    }
    
    private static final Logger LOG = Log.getLogger(QuotesDecoderTest.class);
    
    @Rule
    public TestName testname = new TestName();
    
    private UntrustedWSServer server;
    private WebSocketContainer client;
    
    @Before
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }
    
    @Before
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
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
        assertThat("Quotes Author", quotes.getAuthor(), is("Benjamin Franklin"));
        assertThat("Quotes Count", quotes.getQuotes().size(), is(3));
    
        quotes = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Quotes Author", quotes.getAuthor(), is("Mark Twain"));
        assertThat("Quotes Count", quotes.getQuotes().size(), is(4));
    }
}
