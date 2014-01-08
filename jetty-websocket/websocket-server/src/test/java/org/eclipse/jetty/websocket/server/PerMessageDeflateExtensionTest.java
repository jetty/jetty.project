//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.HttpResponse;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.server.helper.EchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class PerMessageDeflateExtensionTest
{
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Default configuration for permessage-deflate
     */
    @Test
    public void testPerMessgeDeflateDefault() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.clearExtensions();
        client.addExtensions("permessage-deflate");
        client.setProtocols("echo");

        try
        {
            // Make sure the read times out if there are problems with the implementation
            client.setTimeout(TimeUnit.SECONDS,1);
            client.connect();
            client.sendStandardRequest();
            HttpResponse resp = client.expectUpgradeResponse();

            Assert.assertThat("Response",resp.getExtensionsHeader(),containsString("permessage-deflate"));

            String msg = "Hello";

            // Client sends first message
            client.write(new TextFrame().setPayload(msg));

            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,1000);
            WebSocketFrame frame = capture.getFrames().poll();
            Assert.assertThat("TEXT.payload",frame.getPayloadAsUTF8(),is(msg.toString()));

            // Client sends second message
            client.clearCaptured();
            msg = "There";
            client.write(new TextFrame().setPayload(msg));

            capture = client.readFrames(1,TimeUnit.SECONDS,1);
            frame = capture.getFrames().poll();
            Assert.assertThat("TEXT.payload",frame.getPayloadAsUTF8(),is(msg.toString()));
        }
        finally
        {
            client.close();
        }
    }
}
