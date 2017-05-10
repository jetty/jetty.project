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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

import sun.plugin2.message.Pipe;

public class ParserCapture implements Parser.Handler
{
    public BlockingQueue<WebSocketFrame> framesQueue = new LinkedBlockingDeque<>();
    private Pipe frames;
    
    public void assertHasFrame(byte opCode, int expectedCount)
    {
        int count = 0;
        for(WebSocketFrame frame: framesQueue)
        {
            if(frame.getOpCode() == opCode)
                count++;
        }
        assertThat("Frames[op=" + opCode + "].count", count, is(expectedCount));
    }
    
    @Deprecated
    public BlockingQueue<WebSocketFrame> getFrames()
    {
        return framesQueue;
    }
    
    @Override
    public boolean onFrame(Frame frame)
    {
        framesQueue.offer(WebSocketFrame.copy(frame));
        return true; // it is consumed
    }
}
