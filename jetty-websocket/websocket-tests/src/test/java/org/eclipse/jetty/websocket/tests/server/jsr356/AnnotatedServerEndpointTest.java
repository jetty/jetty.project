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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.WSConstants;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.eclipse.jetty.websocket.tests.jsr356.coders.DateDecoder;
import org.eclipse.jetty.websocket.tests.jsr356.coders.TimeEncoder;
import org.eclipse.jetty.websocket.tests.server.jsr356.configs.EchoSocketConfigurator;
import org.eclipse.jetty.websocket.tests.server.jsr356.sockets.ConfiguredEchoSocket;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Example of an annotated echo server discovered via annotation scanning.
 */
public class AnnotatedServerEndpointTest
{
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");
    
    private static WSServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(AnnotatedServerEndpointTest.class.getName());
        server = new WSServer(testdir, "app");
        server.createWebInf();
        server.copyEndpoint(ConfiguredEchoSocket.class);
        server.copyClass(EchoSocketConfigurator.class);
        server.copyClass(DateDecoder.class);
        server.copyClass(TimeEncoder.class);
        
        server.start();
        
        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    private void assertResponse(String message, String expectedText) throws Exception
    {
        Map<String, String> upgradeRequest = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeRequest.put(WSConstants.SEC_WEBSOCKET_PROTOCOL, "echo");
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(message));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(expectedText));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer("/app/echo", upgradeRequest))
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    @Test
    public void testConfigurator() throws Exception
    {
        assertResponse("configurator", EchoSocketConfigurator.class.getName());
    }
    
    @Test
    public void testTextMax() throws Exception
    {
        assertResponse("text-max", "111,222");
    }
    
    @Test
    public void testBinaryMax() throws Exception
    {
        assertResponse("binary-max", "333,444");
    }
    
    @Test
    public void testDecoders() throws Exception
    {
        assertResponse("decoders", DateDecoder.class.getName());
    }
    
    @Test
    public void testEncoders() throws Exception
    {
        assertResponse("encoders", TimeEncoder.class.getName());
    }
    
    @Test
    public void testSubProtocols() throws Exception
    {
        assertResponse("subprotocols", "chat, echo, test");
    }
}
