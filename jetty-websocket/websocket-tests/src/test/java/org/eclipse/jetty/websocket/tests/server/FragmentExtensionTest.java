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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConstants;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.UnitExtensionStack;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class FragmentExtensionTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    private String[] split(String str, int partSize)
    {
        int strLength = str.length();
        int count = (int) Math.ceil((double) str.length() / partSize);
        String ret[] = new String[count];
        int idx;
        for (int i = 0; i < count; i++)
        {
            idx = (i * partSize);
            ret[i] = str.substring(idx, Math.min(idx + partSize, strLength));
        }
        return ret;
    }
    
    @Test
    public void testFragmentExtension() throws Exception
    {
        ExtensionFactory extensionFactory = server.getWebSocketServletFactory().getExtensionFactory();
        assertThat("Extension Factory", extensionFactory, notNullValue());
        Assume.assumeTrue("Server has fragment registered", extensionFactory.isAvailable("fragment"));
        
        int fragSize = 4;
        
        String msg = "Sent as a long message that should be split";
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(msg));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        Map<String,String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_EXTENSIONS, "fragment;maxLength=" + fragSize);
    
        try (LocalFuzzer session = server.newLocalFuzzer("/", upgradeHeaders))
        {
            String negotiatedExtensions = session.upgradeResponse.get(WebSocketConstants.SEC_WEBSOCKET_EXTENSIONS);
    
            List<ExtensionConfig> extensionConfigList = ExtensionConfig.parseList(negotiatedExtensions);
            assertThat("Client Upgrade Response.Extensions", extensionConfigList.size(), is(1));
            assertThat("Client Upgrade Response.Extensions[0]", extensionConfigList.get(0).toString(), containsString("fragment"));
    
            UnitExtensionStack extensionStack = UnitExtensionStack.clientBased();
            List<WebSocketFrame> outgoingFrames = extensionStack.processOutgoing(send);
            session.sendBulk(outgoingFrames);
    
            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            BlockingQueue<WebSocketFrame> incomingFrames = extensionStack.processIncoming(framesQueue);
            
            String parts[] = split(msg, fragSize);
            for (int i = 0; i < parts.length; i++)
            {
                WebSocketFrame frame = incomingFrames.poll();
                Assert.assertThat("text[" + i + "].payload", frame.getPayloadAsUTF8(), is(parts[i]));
            }
        }
    }
}
