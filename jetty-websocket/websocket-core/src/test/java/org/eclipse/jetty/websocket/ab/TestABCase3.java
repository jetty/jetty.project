// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.ab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.UnitGenerator;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test various invalid frame situations
 */
@RunWith(value = Parameterized.class)
public class TestABCase3
{

    @Parameters
    public static Collection<WebSocketFrame[]> data()
    {
        List<WebSocketFrame[]> data = new ArrayList<>();
        // @formatter:off
        data.add(new WebSocketFrame[]
                { WebSocketFrame.ping().setFin(false) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.ping().setRsv1(true) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.ping().setRsv2(true) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.ping().setRsv3(true) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.pong().setFin(false) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.ping().setRsv1(true) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.pong().setRsv2(true) });
        data.add(new WebSocketFrame[]
                { WebSocketFrame.pong().setRsv3(true) });
        data.add(new WebSocketFrame[]
                { new CloseInfo().asFrame().setFin(false) });
        data.add(new WebSocketFrame[]
                { new CloseInfo().asFrame().setRsv1(true) });
        data.add(new WebSocketFrame[]
                { new CloseInfo().asFrame().setRsv2(true) });
        data.add(new WebSocketFrame[]
                { new CloseInfo().asFrame().setRsv3(true) });
        // @formatter:on
        return data;
    }

    private WebSocketFrame invalidFrame;

    public TestABCase3(WebSocketFrame invalidFrame)
    {
        this.invalidFrame = invalidFrame;
    }

    @Test(expected = ProtocolException.class)
    public void testGenerateInvalidControlFrame()
    {
        Generator generator = new UnitGenerator();

        generator.generate(invalidFrame);
    }


}
