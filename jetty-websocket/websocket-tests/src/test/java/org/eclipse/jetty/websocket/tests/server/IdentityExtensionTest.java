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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConstants;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.UnitExtensionStack;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class IdentityExtensionTest
{
    private static SimpleServletServer server;
    
    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testIdentityExtension() throws Exception
    {
        ExtensionFactory extensionFactory = server.getWebSocketServletFactory().getExtensionFactory();
        assertThat("Extension Factory", extensionFactory, notNullValue());
        assumeTrue(extensionFactory.isAvailable("identity"), "Server has identity registered");
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello Identity"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        Map<String, String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_EXTENSIONS, "identity;param=0, identity;param=1, identity ; param = '2' ; other = ' some = value '");
        
        try (LocalFuzzer session = server.newLocalFuzzer("/", upgradeHeaders))
        {
            String negotiatedExtensions = session.upgradeResponse.get(WebSocketConstants.SEC_WEBSOCKET_EXTENSIONS);
            
            List<ExtensionConfig> extensionConfigList = ExtensionConfig.parseList(negotiatedExtensions);
            assertThat("Client Upgrade Response.Extensions", extensionConfigList.size(), is(3));
            assertThat("Client Upgrade Response.Extensions[0]", extensionConfigList.get(0).toString(), containsString("identity"));
            
            UnitExtensionStack extensionStack = UnitExtensionStack.clientBased();
            List<WebSocketFrame> outgoingFrames = extensionStack.processOutgoing(send);
            session.sendBulk(outgoingFrames);
            
            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            BlockingQueue<WebSocketFrame> incomingFrames = extensionStack.processIncoming(framesQueue);
            
            WebSocketFrame frame = incomingFrames.poll();
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), is("Hello Identity"));
        }
    }
}
