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
package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.masks.FixedMasker;
import org.eclipse.jetty.websocket.masks.RandomMasker;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

public class GeneratorParserRoundtripTest
{
    @Test
    public void testParserAndGenerator() throws Exception
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        StandardByteBufferPool bufferPool = new StandardByteBufferPool();
        Generator gen = new Generator(policy,bufferPool);
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        ByteBuffer out = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            // Generate Buffer
            BufferUtil.flipToFill(out);
            WebSocketFrame frame = WebSocketFrame.text(message);
            out = gen.generate(frame);

            // Parse Buffer
            parser.parse(out);
        }
        finally
        {
            bufferPool.release(out);
        }

        // Validate
        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame txt = capture.getFrames().get(0);
        Assert.assertThat("Text parsed",txt.getPayloadAsUTF8(),is(message));
    }

    @Test
    public void testParserAndGeneratorMasked() throws Exception
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        policy.setMasker(new RandomMasker());

        StandardByteBufferPool bufferPool = new StandardByteBufferPool();
        Generator gen = new Generator(policy,bufferPool);
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        ByteBuffer out = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            // Setup Frame
            WebSocketFrame frame = WebSocketFrame.text(message);

            // Add masking
            byte mask[] = new byte[4];
            new FixedMasker().genMask(mask);
            frame.setMask(mask);

            // Generate Buffer
            out = gen.generate(policy.getBufferSize(),frame);

            // Parse Buffer
            parser.parse(out);
        }
        finally
        {
            bufferPool.release(out);
        }

        // Validate
        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame txt = capture.getFrames().get(0);
        Assert.assertTrue("Text.isMasked",txt.isMasked());
        Assert.assertThat("Text parsed",txt.getPayloadAsUTF8(),is(message));
    }
}
