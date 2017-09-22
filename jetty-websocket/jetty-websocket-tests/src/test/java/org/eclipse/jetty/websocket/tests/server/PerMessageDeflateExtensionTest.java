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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class PerMessageDeflateExtensionTest
{
    @WebSocket
    public static class BinaryHashSocket
    {
        @OnWebSocketMessage
        public void onBinary(Session session, ByteBuffer data)
        {
            try
            {
                byte buf[] = BufferUtil.toArray(data);
                String sha1 = Sha1Sum.calculate(buf);
                session.getRemote().sendString(String.format("binary[sha1=%s]", sha1));
            }
            catch (Throwable t)
            {
                session.close(StatusCode.SERVER_ERROR, t.getMessage());
            }
        }
    }
    
    public static class BinaryHashServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.getPolicy().setMaxBinaryMessageSize(2 * 1024 * 1024);
            factory.register(BinaryHashSocket.class);
        }
    }
    
    private enum TestCaseMessageSize
    {
        TINY(10),
        SMALL(1024),
        MEDIUM(10 * 1024),
        LARGE(100 * 1024),
        HUGE(1024 * 1024);
        
        private int size;
        
        TestCaseMessageSize(int size)
        {
            this.size = size;
        }
    }
    
    @Parameters(name = "{0} ({3}) (Input Buffer Size: {4} bytes)")
    public static List<Object[]> modes()
    {
        List<Object[]> modes = new ArrayList<>();
        
        for (TestCaseMessageSize size : TestCaseMessageSize.values())
        {
            modes.add(new Object[]{"Normal HTTP/WS", false, "ws", size, -1});
            modes.add(new Object[]{"Encrypted HTTPS/WSS", true, "wss", size, -1});
            int altInputBufSize = 15 * 1024;
            modes.add(new Object[]{"Normal HTTP/WS", false, "ws", size, altInputBufSize});
            modes.add(new Object[]{"Encrypted HTTPS/WSS", true, "wss", size, altInputBufSize});
        }
        
        return modes;
    }
    
    private SimpleServletServer server;
    private String scheme;
    private int msgSize;
    private int inputBufferSize;
    
    public PerMessageDeflateExtensionTest(String testId, boolean sslMode, String scheme, TestCaseMessageSize msgSize, int bufferSize) throws Exception
    {
        server = new SimpleServletServer(new BinaryHashServlet());
        server.enableSsl(sslMode);
        server.start();
        
        this.scheme = scheme;
        this.msgSize = msgSize.size;
        this.inputBufferSize = bufferSize;
    }
    
    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    /**
     * Default configuration for permessage-deflate
     *
     * @throws Exception on test failure
     */
    @Test
    public void testPerMessageDeflateDefault() throws Exception
    {
        Assume.assumeTrue("Server has permessage-deflate registered",
                server.getWebSocketServletFactory().getExtensionRegistry().isAvailable("permessage-deflate"));
        
        Assert.assertThat("server scheme", server.getServerUri().getScheme(), is(scheme));
        
        int binBufferSize = (int) (msgSize * 1.5);
        
        WebSocketPolicy serverPolicy = server.getWebSocketServletFactory().getPolicy();
        
        // Ensure binBufferSize is sane (not smaller then other buffers)
        binBufferSize = Math.max(binBufferSize, serverPolicy.getMaxBinaryMessageSize());
        binBufferSize = Math.max(binBufferSize, this.inputBufferSize);
        
        serverPolicy.setMaxBinaryMessageSize(binBufferSize);

        HttpClient httpClient = new HttpClient(server.getSslContextFactory());
        WebSocketClient client = new WebSocketClient(httpClient);
        WebSocketPolicy clientPolicy = client.getPolicy();
        clientPolicy.setMaxBinaryMessageSize(binBufferSize);
        if (inputBufferSize > 0)
        {
            clientPolicy.setInputBufferSize(inputBufferSize);
        }
        
        try
        {
            client.start();
            // Make sure the read times out if there are problems with the implementation
            client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(15));
            
            TrackingEndpoint clientSocket = new TrackingEndpoint("Client");
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.addExtensions("permessage-deflate");
            request.setSubProtocols("echo");
            
            Future<Session> fut = client.connect(clientSocket, server.getServerUri(), request);
            
            // Wait for connect
            Session session = fut.get(30, TimeUnit.SECONDS);
            
            assertThat("Response.extensions", getNegotiatedExtensionList(session), containsString("permessage-deflate"));
            
            // Create message
            byte msg[] = new byte[msgSize];
            Random rand = new Random();
            rand.setSeed(8080);
            rand.nextBytes(msg);
            
            // Calculate sha1
            String sha1 = Sha1Sum.calculate(msg);
            
            // Client sends first message
            session.getRemote().sendBytes(ByteBuffer.wrap(msg));
            
            String echoMsg = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
            Assert.assertThat("Echo'd Message", echoMsg, is("binary[sha1=" + sha1 + "]"));
        }
        finally
        {
            client.stop();
        }
    }
    
    private String getNegotiatedExtensionList(Session session)
    {
        StringBuilder actual = new StringBuilder();
        actual.append('[');
        
        boolean delim = false;
        for (ExtensionConfig ext : session.getUpgradeResponse().getExtensions())
        {
            if (delim)
                actual.append(", ");
            actual.append(ext.getName());
            delim = true;
        }
        actual.append(']');
        
        return actual.toString();
    }
}
