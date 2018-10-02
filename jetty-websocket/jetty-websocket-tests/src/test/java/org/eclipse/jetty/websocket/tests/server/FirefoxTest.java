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
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.tests.TestWebSocket;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.junit.jupiter.api.Test;

public class FirefoxTest extends AbstractLocalServerCase
{
    @Test
    public void testConnectionKeepAlive() throws Exception
    {
        String msg = "this is an echo ... cho ... ho ... o";

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(msg));
        send.add(CloseStatus.toFrame(StatusCode.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(msg));
        expect.add(CloseStatus.toFrame(StatusCode.NORMAL));

        Map<String,String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        // REGRESSION TEST - Odd Connection Header value seen in Firefox
        upgradeHeaders.put("Connection", "keep-alive, Upgrade");

        try (TestWebSocket session = server.newClient("/", upgradeHeaders))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
