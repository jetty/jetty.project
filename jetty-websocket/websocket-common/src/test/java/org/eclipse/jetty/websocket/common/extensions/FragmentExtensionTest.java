//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.fragment.FragmentExtension;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.common.test.OutgoingFramesCapture;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class FragmentExtensionTest
{
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    /**
     * Verify that incoming frames are passed thru without modification
     */
    @Test
    public void testIncomingFrames()
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(WebSocketPolicy.newClientPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        ext.setConfig(config);

        ext.setNextIncomingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Manually create frame and pass into extension
        for (String q : quote)
        {
            Frame frame = new TextFrame().setPayload(q);
            ext.incomingFrame(frame);
        }

        int len = quote.size();
        capture.assertFrameCount(len);
        capture.assertHasFrame(OpCode.TEXT, len);

        String prefix;
        int i = 0;
        for (WebSocketFrame actual : capture.getFrames())
        {
            prefix = "Frame[" + i + "]";

            Assert.assertThat(prefix + ".opcode", actual.getOpCode(), is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin", actual.isFin(), is(true));
            Assert.assertThat(prefix + ".rsv1", actual.isRsv1(), is(false));
            Assert.assertThat(prefix + ".rsv2", actual.isRsv2(), is(false));
            Assert.assertThat(prefix + ".rsv3", actual.isRsv3(), is(false));

            ByteBuffer expected = BufferUtil.toBuffer(quote.get(i), StandardCharsets.UTF_8);
            Assert.assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expected, actual.getPayload().slice());
            i++;
        }
    }

    /**
     * Incoming PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testIncomingPing()
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        ext.setConfig(config);

        ext.setNextIncomingFrames(capture);

        String payload = "Are you there?";
        Frame ping = new PingFrame().setPayload(payload);
        ext.incomingFrame(ping);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.PING, 1);
        WebSocketFrame actual = capture.getFrames().poll();

        Assert.assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.PING));
        Assert.assertThat("Frame.fin", actual.isFin(), is(true));
        Assert.assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        Assert.assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        Assert.assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload, StandardCharsets.UTF_8);
        Assert.assertThat("Frame.payloadLength", actual.getPayloadLength(), is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload", expected, actual.getPayload().slice());
    }

    /**
     * Verify that outgoing text frames are fragmented by the maxLength configuration.
     * @throws IOException on test failure
     */
    @Test
    public void testOutgoingFramesByMaxLength() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=20");
        ext.setConfig(config);

        ext.setNextOutgoingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Write quote as separate frames
        for (String section : quote)
        {
            Frame frame = new TextFrame().setPayload(section);
            ext.outgoingFrame(frame, null, BatchMode.OFF);
        }

        // Expected Frames
        List<WebSocketFrame> expectedFrames = new ArrayList<>();
        expectedFrames.add(new TextFrame().setPayload("No amount of experim").setFin(false));
        expectedFrames.add(new ContinuationFrame().setPayload("entation can ever pr").setFin(false));
        expectedFrames.add(new ContinuationFrame().setPayload("ove me right;").setFin(true));

        expectedFrames.add(new TextFrame().setPayload("a single experiment ").setFin(false));
        expectedFrames.add(new ContinuationFrame().setPayload("can prove me wrong.").setFin(true));

        expectedFrames.add(new TextFrame().setPayload("-- Albert Einstein").setFin(true));

        // capture.dump();

        int len = expectedFrames.size();
        capture.assertFrameCount(len);

        String prefix;
        LinkedList<WebSocketFrame> frames = capture.getFrames();
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";
            WebSocketFrame actualFrame = frames.get(i);
            WebSocketFrame expectedFrame = expectedFrames.get(i);

            // System.out.printf("actual: %s%n",actualFrame);
            // System.out.printf("expect: %s%n",expectedFrame);

            // Validate Frame
            Assert.assertThat(prefix + ".opcode", actualFrame.getOpCode(), is(expectedFrame.getOpCode()));
            Assert.assertThat(prefix + ".fin", actualFrame.isFin(), is(expectedFrame.isFin()));
            Assert.assertThat(prefix + ".rsv1", actualFrame.isRsv1(), is(expectedFrame.isRsv1()));
            Assert.assertThat(prefix + ".rsv2", actualFrame.isRsv2(), is(expectedFrame.isRsv2()));
            Assert.assertThat(prefix + ".rsv3", actualFrame.isRsv3(), is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            Assert.assertThat(prefix + ".payloadLength", actualData.remaining(), is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expectedData, actualData);
        }
    }

    /**
     * Verify that outgoing text frames are fragmented by default configuration
     * @throws IOException on test failure
     */
    @Test
    public void testOutgoingFramesDefaultConfig() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment");
        ext.setConfig(config);

        ext.setNextOutgoingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Write quote as separate frames
        for (String section : quote)
        {
            Frame frame = new TextFrame().setPayload(section);
            ext.outgoingFrame(frame, null, BatchMode.OFF);
        }

        // Expected Frames
        List<WebSocketFrame> expectedFrames = new ArrayList<>();
        expectedFrames.add(new TextFrame().setPayload("No amount of experimentation can ever prove me right;"));
        expectedFrames.add(new TextFrame().setPayload("a single experiment can prove me wrong."));
        expectedFrames.add(new TextFrame().setPayload("-- Albert Einstein"));

        // capture.dump();

        int len = expectedFrames.size();
        capture.assertFrameCount(len);

        String prefix;
        LinkedList<WebSocketFrame> frames = capture.getFrames();
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";
            WebSocketFrame actualFrame = frames.get(i);
            WebSocketFrame expectedFrame = expectedFrames.get(i);

            // Validate Frame
            Assert.assertThat(prefix + ".opcode", actualFrame.getOpCode(), is(expectedFrame.getOpCode()));
            Assert.assertThat(prefix + ".fin", actualFrame.isFin(), is(expectedFrame.isFin()));
            Assert.assertThat(prefix + ".rsv1", actualFrame.isRsv1(), is(expectedFrame.isRsv1()));
            Assert.assertThat(prefix + ".rsv2", actualFrame.isRsv2(), is(expectedFrame.isRsv2()));
            Assert.assertThat(prefix + ".rsv3", actualFrame.isRsv3(), is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            Assert.assertThat(prefix + ".payloadLength", actualData.remaining(), is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expectedData, actualData);
        }
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     * @throws IOException on test failure
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        ext.setConfig(config);

        ext.setNextOutgoingFrames(capture);

        String payload = "Are you there?";
        Frame ping = new PingFrame().setPayload(payload);

        ext.outgoingFrame(ping, null, BatchMode.OFF);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.PING, 1);

        WebSocketFrame actual = capture.getFrames().getFirst();

        Assert.assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.PING));
        Assert.assertThat("Frame.fin", actual.isFin(), is(true));
        Assert.assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        Assert.assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        Assert.assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload, StandardCharsets.UTF_8);
        Assert.assertThat("Frame.payloadLength", actual.getPayloadLength(), is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload", expected, actual.getPayload().slice());
    }
}
