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

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.junit.Assert;
import org.junit.Test;

public class LiveChromiumRequestParserTest
{
    @Test
    public void testSynStream() throws Exception
    {
        // Bytes taken with wireshark from a live chromium request
        byte[] bytes1 = toBytes("" +
                "800200010100011a0000000100000000000038eadfa251b262e0626083a41706" +
                "7bb80b75302cd6ae4017cdcdb12eb435d0b3d4d1d2d702b32c18f850732c036f" +
                "68889bae850e44da94811f2d0b3308821ca80375a14e714a72065c0d2cd619f8" +
                "52f37443837552f3a076b080b234033f28de73404c2b43630b135306b65c6059" +
                "929fc2c0ecee1ac2c0560c4c7eb9a940b52525050ccc206f32ea337021f22643" +
                "bb6f7e55664e4ea2bea99e81824684a1a135400a3e9979a5150a151666f16626" +
                "9a0a8e40afa686a726796796e89b1a9bea992b68787b84f8fae828e46466a72a" +
                "b8a72667e76b2a842695e69594ea1b0203d640c1390358e06496e6ea1b9ae901" +
                "c3c5d048cfdc1c22988a22149c98965894093195811d1a150c1cb01802000000" +
                "ffff");
        byte[] bytes2 = toBytes("" +
                "800200010100002700000003000000008000428a106660d00ee640e5d14f4b2c" +
                "cb0466313d203154c217000000ffff");

        final AtomicReference<ControlFrame> frameRef = new AtomicReference<>();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                frameRef.set(frame);
            }
        });
        parser.parse(ByteBuffer.wrap(bytes1));

        ControlFrame frame = frameRef.get();
        Assert.assertNotNull(frame);
        Assert.assertEquals(ControlFrameType.SYN_STREAM, frame.getType());
        SynStreamFrame synStream = (SynStreamFrame)frame;
        Assert.assertEquals(2, synStream.getVersion());
        Assert.assertEquals(1, synStream.getStreamId());
        Assert.assertEquals(0, synStream.getAssociatedStreamId());
        Assert.assertEquals(0, synStream.getPriority());
        Assert.assertNotNull(synStream.getHeaders());
        Assert.assertFalse(synStream.getHeaders().isEmpty());

        frameRef.set(null);
        parser.parse(ByteBuffer.wrap(bytes2));

        frame = frameRef.get();
        Assert.assertNotNull(frame);
        Assert.assertEquals(ControlFrameType.SYN_STREAM, frame.getType());
        synStream = (SynStreamFrame)frame;
        Assert.assertEquals(2, synStream.getVersion());
        Assert.assertEquals(3, synStream.getStreamId());
        Assert.assertEquals(0, synStream.getAssociatedStreamId());
        Assert.assertEquals(2, synStream.getPriority());
        Assert.assertNotNull(synStream.getHeaders());
        Assert.assertFalse(synStream.getHeaders().isEmpty());
    }

    private byte[] toBytes(String hexs)
    {
        byte[] bytes = new byte[hexs.length() / 2];
        for (int i = 0; i < hexs.length(); i += 2)
        {
            String hex = hexs.substring(i, i + 2);
            bytes[i / 2] = (byte)Integer.parseInt(hex, 16);
        }
        return bytes;
    }
}
