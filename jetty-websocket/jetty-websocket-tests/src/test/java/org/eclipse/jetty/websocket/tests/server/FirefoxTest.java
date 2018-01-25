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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.tests.Fuzzer;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.junit.Test;

public class FirefoxTest extends AbstractLocalServerCase
{
    @Test
    public void testConnectionKeepAlive() throws Exception
    {
        String msg = "this is an echo ... cho ... ho ... o";
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(msg));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(msg));
        expect.add(new CloseFrame().setPayload(StatusCode.NORMAL.getCode()));
    
        Map<String,String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        // REGRESSION TEST - Odd Connection Header value seen in Firefox
        upgradeHeaders.put("Connection", "keep-alive, Upgrade");
    
        try (Fuzzer session = server.newLocalFuzzer("/", upgradeHeaders))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
