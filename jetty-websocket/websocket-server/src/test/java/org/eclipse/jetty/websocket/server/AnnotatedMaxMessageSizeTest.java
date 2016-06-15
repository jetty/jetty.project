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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.server.examples.echo.BigEchoSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class AnnotatedMaxMessageSizeTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    
    private static Server server;
    private static ServerConnector connector;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.register(BigEchoSocket.class);
            }
        };

        server.setHandler(wsHandler);
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEchoGood() throws IOException, Exception
    {
        BlockheadClient client = new BlockheadClient(serverUri);
        try
        {
            client.setProtocols("echo");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }
    
    @Test(timeout=8000)
    public void testEchoTooBig() throws IOException, Exception
    {
        BlockheadClient client = new BlockheadClient(serverUri);
        try(StacklessLogging logging = new StacklessLogging(Parser.class))
        {
            client.setProtocols("echo");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            int size = 120 * 1024;
            byte buf[] = new byte[size]; // buffer bigger than maxMessageSize
            Arrays.fill(buf,(byte)'x');
            client.write(new TextFrame().setPayload(ByteBuffer.wrap(buf)));

            // Read frame (hopefully close frame saying its too large)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Frame is close", tf.getOpCode(), is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(tf);
            Assert.assertThat("Close Code", close.getStatusCode(), is(StatusCode.MESSAGE_TOO_LARGE));
        }
        finally
        {
            client.close();
        }
    }
}
