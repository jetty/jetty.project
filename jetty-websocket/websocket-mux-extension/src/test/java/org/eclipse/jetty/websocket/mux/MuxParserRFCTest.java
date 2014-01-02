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

package org.eclipse.jetty.websocket.mux;

import static org.hamcrest.Matchers.is;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;
import org.eclipse.jetty.websocket.mux.helper.IncomingFramesCapture;
import org.eclipse.jetty.websocket.mux.helper.UnitParser;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class MuxParserRFCTest
{
    public static class DummyMuxExtension extends AbstractMuxExtension
    {
        @Override
        public void configureMuxer(Muxer muxer)
        {
            /* nothing to do */
        }
    }

    private LinkedList<WebSocketFrame> asFrames(byte[] buf)
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        Parser parser = new UnitParser(policy);
        parser.setIncomingFramesHandler(capture);
        List<? extends AbstractExtension> muxList = Collections.singletonList(new DummyMuxExtension());
        parser.configureFromExtensions(muxList);
        ByteBuffer bbuf = ByteBuffer.wrap(buf);
        parser.parse(bbuf);

        return capture.getFrames();
    }

    private boolean isHexOnly(String part)
    {
        Pattern bytePat = Pattern.compile("(\\s*0x[0-9A-Fa-f]{2}+){1,}+");
        Matcher mat = bytePat.matcher(part);
        return mat.matches();
    }

    private MuxEventCapture parseMuxFrames(LinkedList<WebSocketFrame> frames)
    {
        MuxParser parser = new MuxParser();
        MuxEventCapture capture = new MuxEventCapture();
        parser.setEvents(capture);
        for(Frame frame: frames) {
            parser.parse(frame);
        }
        return capture;
    }

    @Test
    public void testIsHexOnly()
    {
        Assert.assertTrue(isHexOnly("0x00"));
        Assert.assertTrue(isHexOnly("0x00 0xaF"));
        Assert.assertFalse(isHexOnly("Hello World"));
    }

    @Test
    @Ignore
    public void testRFCExample1() throws IOException
    {
        // Create RFC detailed frames
        byte buf[] = toByteArray("0x82 0x0d 0x01 0x81","Hello world");
        LinkedList<WebSocketFrame> frames = asFrames(buf);
        Assert.assertThat("Frame count",frames.size(),is(1));

        // Have mux parse frames
        MuxEventCapture capture = parseMuxFrames(frames);
        capture.assertFrameCount(1);

        MuxedFrame mux;

        mux = capture.getFrames().pop();
        String prefix = "MuxFrame[0]";
        Assert.assertThat(prefix + ".channelId",mux.getChannelId(),is(1L));
        Assert.assertThat(prefix + ".fin",mux.isFin(),is(true));
        Assert.assertThat(prefix + ".rsv1",mux.isRsv1(),is(false));
        Assert.assertThat(prefix + ".rsv2",mux.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",mux.isRsv3(),is(false));
        Assert.assertThat(prefix + ".masked",mux.isMasked(),is(false));
        Assert.assertThat(prefix + ".opcode",mux.getOpCode(),is(OpCode.TEXT));

        String payload = mux.getPayloadAsUTF8();
        Assert.assertThat(prefix + ".payload/text",payload,is("Hello world"));
    }

    @Test
    @Ignore
    public void testRFCExample2() throws IOException
    {
        // Create RFC detailed frames
        byte buf[] = toByteArray("0x02 0x07 0x01 0x81","Hello","0x80 0x06"," world");
        LinkedList<WebSocketFrame> frames = asFrames(buf);
        Assert.assertThat("Frame count",frames.size(),is(2));

        // Have mux parse frames
        MuxEventCapture capture = parseMuxFrames(frames);
        capture.assertFrameCount(2);

        MuxedFrame mux;

        // Text Frame
        mux = capture.getFrames().get(0);
        String prefix = "MuxFrame[0]";
        Assert.assertThat(prefix + ".channelId",mux.getChannelId(),is(1L));
        // (BUG IN DRAFT) Assert.assertThat(prefix + ".fin",mux.isFin(),is(false));
        Assert.assertThat(prefix + ".rsv1",mux.isRsv1(),is(false));
        Assert.assertThat(prefix + ".rsv2",mux.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",mux.isRsv3(),is(false));
        Assert.assertThat(prefix + ".masked",mux.isMasked(),is(false));
        Assert.assertThat(prefix + ".opcode",mux.getOpCode(),is(OpCode.TEXT));

        String payload = mux.getPayloadAsUTF8();
        Assert.assertThat(prefix + ".payload/text",payload,is("Hello"));

        // Continuation Frame
        mux = capture.getFrames().get(1);
        prefix = "MuxFrame[1]";
        // (BUG IN DRAFT) Assert.assertThat(prefix + ".channelId",mux.getChannelId(),is(1L));
        // (BUG IN DRAFT) Assert.assertThat(prefix + ".fin",mux.isFin(),is(true));
        Assert.assertThat(prefix + ".rsv1",mux.isRsv1(),is(false));
        Assert.assertThat(prefix + ".rsv2",mux.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",mux.isRsv3(),is(false));
        Assert.assertThat(prefix + ".masked",mux.isMasked(),is(false));
        // (BUG IN DRAFT) Assert.assertThat(prefix + ".opcode",mux.getOpCode(),is(OpCode.BINARY));

        payload = mux.getPayloadAsUTF8();
        Assert.assertThat(prefix + ".payload/text",payload,is(" world"));
    }

    @Test
    @Ignore
    public void testRFCExample3() throws IOException
    {
        // Create RFC detailed frames
        byte buf[] = toByteArray("0x82 0x07 0x01 0x01","Hello","0x82 0x05 0x02 0x81","bye","0x82 0x08 0x01 0x80"," world");
        LinkedList<WebSocketFrame> frames = asFrames(buf);
        Assert.assertThat("Frame count",frames.size(),is(3));

        // Have mux parse frames
        MuxEventCapture capture = parseMuxFrames(frames);
        capture.assertFrameCount(3);

        MuxedFrame mux;

        // Text Frame (Message 1)
        mux = capture.getFrames().pop();
        String prefix = "MuxFrame[0]";
        Assert.assertThat(prefix + ".channelId",mux.getChannelId(),is(1L));
        Assert.assertThat(prefix + ".fin",mux.isFin(),is(false));
        Assert.assertThat(prefix + ".rsv1",mux.isRsv1(),is(false));
        Assert.assertThat(prefix + ".rsv2",mux.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",mux.isRsv3(),is(false));
        Assert.assertThat(prefix + ".masked",mux.isMasked(),is(false));
        Assert.assertThat(prefix + ".opcode",mux.getOpCode(),is(OpCode.TEXT));

        String payload = mux.getPayloadAsUTF8();
        Assert.assertThat(prefix + ".payload/text",payload,is("Hello"));

        // Text Frame (Message 2)
        mux = capture.getFrames().pop();
        prefix = "MuxFrame[1]";
        Assert.assertThat(prefix + ".channelId",mux.getChannelId(),is(2L));
        Assert.assertThat(prefix + ".fin",mux.isFin(),is(true));
        Assert.assertThat(prefix + ".rsv1",mux.isRsv1(),is(false));
        Assert.assertThat(prefix + ".rsv2",mux.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",mux.isRsv3(),is(false));
        Assert.assertThat(prefix + ".masked",mux.isMasked(),is(false));
        Assert.assertThat(prefix + ".opcode",mux.getOpCode(),is(OpCode.TEXT));

        payload = mux.getPayloadAsUTF8();
        Assert.assertThat(prefix + ".payload/text",payload,is("bye"));

        // Continuation Frame (Message 1)
        mux = capture.getFrames().pop();
        prefix = "MuxFrame[2]";
        Assert.assertThat(prefix + ".channelId",mux.getChannelId(),is(1L));
        Assert.assertThat(prefix + ".fin",mux.isFin(),is(true));
        Assert.assertThat(prefix + ".rsv1",mux.isRsv1(),is(false));
        Assert.assertThat(prefix + ".rsv2",mux.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",mux.isRsv3(),is(false));
        Assert.assertThat(prefix + ".masked",mux.isMasked(),is(false));
        Assert.assertThat(prefix + ".opcode",mux.getOpCode(),is(OpCode.TEXT));

        payload = mux.getPayloadAsUTF8();
        Assert.assertThat(prefix + ".payload/text",payload,is(" world"));
    }

    private byte[] toByteArray(String... parts) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for(String part: parts) {
            if (isHexOnly(part))
            {
                String hexonly = part.replaceAll("\\s*0x","");
                out.write(TypeUtil.fromHexString(hexonly));
            }
            else
            {
                out.write(part.getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }
}
