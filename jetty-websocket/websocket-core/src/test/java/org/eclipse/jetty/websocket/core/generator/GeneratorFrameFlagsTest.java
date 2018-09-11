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

package org.eclipse.jetty.websocket.core.generator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.AbstractTestFrameHandler;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.junit.Rule;
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
    @Rule
    public TestTracker tracker = new TestTracker();

    private static ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final WebSocketChannel channel;

    @Parameters
    public static Collection<Frame[]> data()
    {
        List<Frame[]> data = new ArrayList<>();
        data.add(new Frame[]{new Frame(OpCode.PING).setFin(false)});
        data.add(new Frame[]{new Frame(OpCode.PING).setRsv1(true)});
        data.add(new Frame[]{new Frame(OpCode.PING).setRsv2(true)});
        data.add(new Frame[]{new Frame(OpCode.PING).setRsv3(true)});
        data.add(new Frame[]{new Frame(OpCode.PONG).setFin(false)});
        data.add(new Frame[]{new Frame(OpCode.PING).setRsv1(true)});
        data.add(new Frame[]{new Frame(OpCode.PONG).setRsv2(true)});
        data.add(new Frame[]{new Frame(OpCode.PONG).setRsv3(true)});
        data.add(new Frame[]{new Frame(OpCode.CLOSE).setFin(false)});
        data.add(new Frame[]{new Frame(OpCode.CLOSE).setRsv1(true)});
        data.add(new Frame[]{new Frame(OpCode.CLOSE).setRsv2(true)});
        data.add(new Frame[]{new Frame(OpCode.CLOSE).setRsv3(true)});
        return data;
    }
    
    private Frame invalidFrame;
    
    public GeneratorFrameFlagsTest(Frame invalidFrame)
    {
        this.invalidFrame = invalidFrame;

        ByteBufferPool bufferPool = new MappedByteBufferPool();
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        ExtensionStack exStack = new ExtensionStack(new WebSocketExtensionRegistry());
        exStack.negotiate(new DecoratedObjectFactory(), policy, bufferPool, new LinkedList<>());
        this.channel = new WebSocketChannel(new AbstractTestFrameHandler(), policy, exStack, "");
    }
    
    @Test(expected = ProtocolException.class)
    public void testGenerateInvalidControlFrame()
    {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        channel.assertValidOutgoing(invalidFrame);
        new Generator(WebSocketPolicy.newServerPolicy(), bufferPool).generateWholeFrame(invalidFrame, buffer);
    }
}
