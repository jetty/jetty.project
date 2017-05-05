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

import static org.hamcrest.Matchers.containsString;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.EncodeException;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.tests.jsr356.AbstractJsrTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.jsr356.coders.Quotes;
import org.eclipse.jetty.websocket.tests.jsr356.coders.QuotesEncoder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class QuotesEncoderTest
{
    @ClientEndpoint(encoders = QuotesEncoder.class, subprotocols = "echo")
    public static class QuotesSocket extends AbstractJsrTrackingEndpoint
    {
        public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
        
        public QuotesSocket(String id)
        {
            super(id);
        }
        
        @SuppressWarnings("unused")
        @OnMessage
        public void onMessage(String message)
        {
            messageQueue.offer(message);
        }
        
        public void write(Quotes quotes) throws IOException, EncodeException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Writing Quotes: {}", quotes);
            this.session.getBasicRemote().sendObject(quotes);
        }
    }
    
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
    
    private void assertReceivedQuotes(String result, Quotes quotes)
    {
        Assert.assertThat("Quote Author", result, containsString("Author: " + quotes.getAuthor()));
        for (String quote : quotes.getQuotes())
        {
            Assert.assertThat("Quote", result, containsString("Quote: " + quote));
        }
    }
    
    @SuppressWarnings("Duplicates")
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
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
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
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
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
