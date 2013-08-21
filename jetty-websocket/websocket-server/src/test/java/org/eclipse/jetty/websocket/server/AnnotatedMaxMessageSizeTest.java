//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.examples.echo.BigEchoSocket;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class AnnotatedMaxMessageSizeTest
{
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
    public void testEcho() throws IOException, Exception
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
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame tf = capture.getFrames().poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }
}
