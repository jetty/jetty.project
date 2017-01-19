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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.HttpResponse;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChromeTest
{
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new MyEchoServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    public void testUpgradeWithWebkitDeflateExtension() throws Exception
    {
        Assume.assumeTrue("Server has x-webkit-deflate-frame registered",
                server.getWebSocketServletFactory().getExtensionFactory().isAvailable("x-webkit-deflate-frame"));
        
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.addExtensions("x-webkit-deflate-frame");
            client.setProtocols("chat");
            client.connect();
            client.sendStandardRequest();
            HttpResponse response = client.expectUpgradeResponse();
            Assert.assertThat("Response",response.getExtensionsHeader(),containsString("x-webkit-deflate-frame"));

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
}
