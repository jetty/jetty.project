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

package org.eclipse.jetty.websocket.core.generator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test various invalid frame situations
 */
@RunWith(value = Parameterized.class)
public class GeneratorFrameFlagsTest
{
    private static ByteBufferPool bufferPool = new MappedByteBufferPool();
    
    @Parameters
    public static Collection<WSFrame[]> data()
    {
        List<WSFrame[]> data = new ArrayList<>();
        data.add(new WSFrame[]{new PingFrame().setFin(false)});
        data.add(new WSFrame[]{new PingFrame().setRsv1(true)});
        data.add(new WSFrame[]{new PingFrame().setRsv2(true)});
        data.add(new WSFrame[]{new PingFrame().setRsv3(true)});
        data.add(new WSFrame[]{new PongFrame().setFin(false)});
        data.add(new WSFrame[]{new PingFrame().setRsv1(true)});
        data.add(new WSFrame[]{new PongFrame().setRsv2(true)});
        data.add(new WSFrame[]{new PongFrame().setRsv3(true)});
        data.add(new WSFrame[]{new CloseFrame().setFin(false)});
        data.add(new WSFrame[]{new CloseFrame().setRsv1(true)});
        data.add(new WSFrame[]{new CloseFrame().setRsv2(true)});
        data.add(new WSFrame[]{new CloseFrame().setRsv3(true)});
        return data;
    }
    
    private WSFrame invalidFrame;
    
    public GeneratorFrameFlagsTest(WSFrame invalidFrame)
    {
        this.invalidFrame = invalidFrame;
    }
    
    @Test(expected = ProtocolException.class)
    public void testGenerateInvalidControlFrame()
    {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        new Generator(WSPolicy.newServerPolicy(), bufferPool).generateWholeFrame(invalidFrame, buffer);
    }
}
