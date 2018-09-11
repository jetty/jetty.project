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

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.parser.ParserCapture;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GeneratorParserRoundtripTest
{
    @Rule
    public TestTracker tracker = new TestTracker();

    private ByteBufferPool bufferPool = new MappedByteBufferPool();
    
    @Test
    public void testParserAndGenerator() throws Exception
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        Generator gen = new Generator(policy,bufferPool);
        ParserCapture capture = new ParserCapture(new Parser(bufferPool));

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        ByteBuffer out = bufferPool.acquire(8192,false);
        try
        {
            // Generate Buffer
            BufferUtil.flipToFill(out);
            Frame frame = new Frame(OpCode.TEXT).setPayload(message);
            ByteBuffer header = gen.generateHeaderBytes(frame);
            ByteBuffer payload = frame.getPayload();
            out.put(header);
            out.put(payload);

            // Parse Buffer
            BufferUtil.flipToFlush(out,0);
            capture.parse(out);
        }
        finally
        {
            bufferPool.release(out);
        }

        // Validate
        Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("Text parsed",txt.getPayloadAsUTF8(),is(message));
    }

    @Test
    public void testParserAndGeneratorMasked() throws Exception
    {
        Generator gen = new Generator(WebSocketPolicy.newClientPolicy(),bufferPool);
        ParserCapture capture = new ParserCapture(new Parser(bufferPool), true, WebSocketBehavior.SERVER);

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        ByteBuffer out = bufferPool.acquire(8192,false);
        BufferUtil.flipToFill(out);
        try
        {
            // Setup Frame
            Frame frame = new Frame(OpCode.TEXT).setPayload(message);

            // Add masking
            byte mask[] = new byte[4];
            Arrays.fill(mask,(byte)0xFF);
            frame.setMask(mask);

            // Generate Buffer
            ByteBuffer header = gen.generateHeaderBytes(frame);
            ByteBuffer payload = frame.getPayload();
            out.put(header);
            out.put(payload);

            // Parse Buffer
            BufferUtil.flipToFlush(out,0);
            capture.parse(out);
        }
        finally
        {
            bufferPool.release(out);
        }

        // Validate
        Frame txt = (Frame)capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertTrue("Text.isMasked",txt.isMasked());
        Assert.assertThat("Text parsed",txt.getPayloadAsUTF8(),is(message));
    }
}
