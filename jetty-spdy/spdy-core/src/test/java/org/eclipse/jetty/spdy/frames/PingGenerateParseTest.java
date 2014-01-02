//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class PingGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        int pingId = 13;
        PingFrame frame1 = new PingFrame(SPDY.V2, pingId);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.PING, frame2.getType());
        PingFrame ping = (PingFrame)frame2;
        Assert.assertEquals(SPDY.V2, ping.getVersion());
        Assert.assertEquals(pingId, ping.getPingId());
        Assert.assertEquals(0, ping.getFlags());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        int pingId = 13;
        PingFrame frame1 = new PingFrame(SPDY.V2, pingId);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.PING, frame2.getType());
        PingFrame ping = (PingFrame)frame2;
        Assert.assertEquals(SPDY.V2, ping.getVersion());
        Assert.assertEquals(pingId, ping.getPingId());
        Assert.assertEquals(0, ping.getFlags());
    }
}
