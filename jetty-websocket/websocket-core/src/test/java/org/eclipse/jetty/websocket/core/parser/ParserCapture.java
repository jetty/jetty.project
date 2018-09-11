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

package org.eclipse.jetty.websocket.core.parser;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.AbstractTestFrameHandler;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class ParserCapture
{
    private final Parser parser;
    private final WebSocketChannel channel;
    public BlockingQueue<Frame> framesQueue = new LinkedBlockingDeque<>();
    public boolean closed = false;
    public boolean copy;
    
    public ParserCapture(Parser parser)
    {
        this(parser,true);
    }

    public ParserCapture(Parser parser, boolean copy)
    {
        this(parser, copy, WebSocketBehavior.CLIENT);
    }
    
    public ParserCapture(Parser parser, boolean copy, WebSocketBehavior behavior)
    {
        this.parser = parser;
        this.copy = copy;

        ByteBufferPool bufferPool = new MappedByteBufferPool();
        WebSocketPolicy policy = new WebSocketPolicy(behavior);
        ExtensionStack exStack = new ExtensionStack(new WebSocketExtensionRegistry());
        exStack.negotiate(new DecoratedObjectFactory(), policy, bufferPool, new LinkedList<>());
        this.channel = new WebSocketChannel(new AbstractTestFrameHandler(), policy, exStack, "");
    }
    
    public void parse(ByteBuffer buffer)
    {
        while(buffer.hasRemaining())
        {
            Frame frame = parser.parse(buffer);
            if (frame==null)
                break;

            channel.assertValidIncoming(frame);

            if (!onFrame(frame))
                break;
        }
    }
        
    public void assertHasFrame(byte opCode, int expectedCount)
    {
        int count = 0;
        for(Frame frame: framesQueue)
        {
            if(frame.getOpCode() == opCode)
                count++;
        }
        assertThat("Frames[op=" + opCode + "].count", count, is(expectedCount));
    }
    
    @Deprecated
    public BlockingQueue<Frame> getFrames()
    {
        return framesQueue;
    }
    
    public boolean onFrame(Frame frame)
    {
        framesQueue.offer(copy?Frame.copy(frame):frame);
        if (frame.getOpCode() == OpCode.CLOSE)
            closed = true;
        return true; // it is consumed
    }
}
