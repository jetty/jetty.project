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
package org.eclipse.jetty.websocket.extensions;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.extensions.fragment.FragmentExtension;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture.Write;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

public class FragmentExtensionTest
{
    public static class ExpectedWrites
    {
        private LinkedList<Write<String>> writes = new LinkedList<>();

        public Write<String> add(String context, Callback<String> callback)
        {
            Write<String> write = new Write<>();
            write.context = context;
            write.callback = callback;
            writes.add(write);
            return write;
        }

        public Write<String> get(int idx)
        {
            return writes.get(idx);
        }

        public int size()
        {
            return writes.size();
        }
    }

    /**
     * Verify that incoming frames are passed thru without modification
     */
    @Test
    public void testIncomingFrames()
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
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
            WebSocketFrame frame = WebSocketFrame.text(q);
            ext.incoming(frame);
        }

        int len = quote.size();
        capture.assertFrameCount(len);
        capture.assertHasFrame(OpCode.TEXT,len);

        String prefix;
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";

            WebSocketFrame actual = capture.getFrames().get(i);

            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(false));
            Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
            Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

            ByteBuffer expected = BufferUtil.toBuffer(quote.get(i),StringUtil.__UTF8_CHARSET);
            Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
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
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        ext.setConfig(config);

        ext.setNextIncomingFrames(capture);

        String payload = "Are you there?";
        WebSocketFrame ping = WebSocketFrame.ping().setPayload(payload);
        ext.incoming(ping);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.PING,1);
        WebSocketFrame actual = capture.getFrames().getFirst();

        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.PING));
        Assert.assertThat("Frame.fin",actual.isFin(),is(true));
        Assert.assertThat("Frame.rsv1",actual.isRsv1(),is(false));
        Assert.assertThat("Frame.rsv2",actual.isRsv2(),is(false));
        Assert.assertThat("Frame.rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload,StringUtil.__UTF8_CHARSET);
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload",expected,actual.getPayload().slice());
    }

    /**
     * Verify that outgoing text frames are fragmented by the maxLength configuration.
     */
    @Test
    public void testOutgoingFramesByMaxLength() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(new StandardByteBufferPool());
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
        List<Callback<String>> callbacks = new ArrayList<>();
        for (String section : quote)
        {
            WebSocketFrame frame = WebSocketFrame.text(section);
            FutureCallback<String> callback = new FutureCallback<>();
            ext.output("Q" + (callbacks.size()),callback,frame);
            callbacks.add(callback);
        }

        // Expected Frames
        ExpectedWrites expectedWrites = new ExpectedWrites();
        expectedWrites.add(null,null).frame = new WebSocketFrame(OpCode.TEXT).setPayload("No amount of experim").setFin(false);
        expectedWrites.add(null,null).frame = new WebSocketFrame(OpCode.CONTINUATION).setPayload("entation can ever pr").setFin(false);
        expectedWrites.add("Q0",callbacks.get(0)).frame = new WebSocketFrame(OpCode.CONTINUATION).setPayload("ove me right;").setFin(true);

        expectedWrites.add(null,null).frame = new WebSocketFrame(OpCode.TEXT).setPayload("a single experiment ").setFin(false);
        expectedWrites.add("Q1",callbacks.get(1)).frame = new WebSocketFrame(OpCode.CONTINUATION).setPayload("can prove me wrong.").setFin(true);

        expectedWrites.add("Q2",callbacks.get(2)).frame = new WebSocketFrame(OpCode.TEXT).setPayload("-- Albert Einstein").setFin(true);

        // capture.dump();

        int len = expectedWrites.size();
        capture.assertFrameCount(len);

        String prefix;
        LinkedList<Write<?>> writes = capture.getWrites();
        for (int i = 0; i < len; i++)
        {
            prefix = "Write[" + i + "]";
            Write<?> actualWrite = writes.get(i);
            Write<String> expectedWrite = expectedWrites.get(i);

            if (expectedWrite.context != null)
            {
                // Validate callbacks have original settings
                Assert.assertThat(prefix + ".context",(String)actualWrite.context,is(expectedWrite.context));
                Assert.assertSame(prefix + ".callback",expectedWrite.callback,actualWrite.callback);
            }

            // Validate Frame
            WebSocketFrame actualFrame = actualWrite.frame;
            WebSocketFrame expectedFrame = expectedWrite.frame;
            prefix += ".frame";
            Assert.assertThat(prefix + ".opcode",actualFrame.getOpCode(),is(expectedFrame.getOpCode()));
            Assert.assertThat(prefix + ".fin",actualFrame.isFin(),is(expectedFrame.isFin()));
            Assert.assertThat(prefix + ".rsv1",actualFrame.isRsv1(),is(expectedFrame.isRsv1()));
            Assert.assertThat(prefix + ".rsv2",actualFrame.isRsv2(),is(expectedFrame.isRsv2()));
            Assert.assertThat(prefix + ".rsv3",actualFrame.isRsv3(),is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            Assert.assertThat(prefix + ".payloadLength",actualData.remaining(),is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expectedData,actualData);
        }
    }

    /**
     * Verify that outgoing text frames are fragmented by default configuration
     */
    @Test
    public void testOutgoingFramesDefaultConfig() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(new StandardByteBufferPool());
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
        List<Callback<String>> callbacks = new ArrayList<>();
        for (String section : quote)
        {
            WebSocketFrame frame = WebSocketFrame.text(section);
            FutureCallback<String> callback = new FutureCallback<>();
            ext.output("Q" + (callbacks.size()),callback,frame);
            callbacks.add(callback);
        }

        // Expected Frames
        ExpectedWrites expectedWrites = new ExpectedWrites();
        expectedWrites.add("Q0",callbacks.get(0)).frame = new WebSocketFrame(OpCode.TEXT).setPayload("No amount of experimentation can ever prove me right;");
        expectedWrites.add("Q1",callbacks.get(1)).frame = new WebSocketFrame(OpCode.TEXT).setPayload("a single experiment can prove me wrong.");
        expectedWrites.add("Q2",callbacks.get(2)).frame = new WebSocketFrame(OpCode.TEXT).setPayload("-- Albert Einstein");

        // capture.dump();

        int len = expectedWrites.size();
        capture.assertFrameCount(len);

        String prefix;
        LinkedList<Write<?>> writes = capture.getWrites();
        for (int i = 0; i < len; i++)
        {
            prefix = "Write[" + i + "]";
            Write<?> actualWrite = writes.get(i);
            Write<String> expectedWrite = expectedWrites.get(i);

            if (expectedWrite.context != null)
            {
                // Validate callbacks have original settings
                Assert.assertThat(prefix + ".context",(String)actualWrite.context,is(expectedWrite.context));
                Assert.assertSame(prefix + ".callback",expectedWrite.callback,actualWrite.callback);
            }

            // Validate Frame
            WebSocketFrame actualFrame = actualWrite.frame;
            WebSocketFrame expectedFrame = expectedWrite.frame;
            prefix += ".frame";
            Assert.assertThat(prefix + ".opcode",actualFrame.getOpCode(),is(expectedFrame.getOpCode()));
            Assert.assertThat(prefix + ".fin",actualFrame.isFin(),is(expectedFrame.isFin()));
            Assert.assertThat(prefix + ".rsv1",actualFrame.isRsv1(),is(expectedFrame.isRsv1()));
            Assert.assertThat(prefix + ".rsv2",actualFrame.isRsv2(),is(expectedFrame.isRsv2()));
            Assert.assertThat(prefix + ".rsv3",actualFrame.isRsv3(),is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            Assert.assertThat(prefix + ".payloadLength",actualData.remaining(),is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expectedData,actualData);
        }
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        ext.setConfig(config);

        ext.setNextOutgoingFrames(capture);

        String payload = "Are you there?";
        WebSocketFrame ping = WebSocketFrame.ping().setPayload(payload);

        FutureCallback<String> callback = new FutureCallback<>();
        ext.output("TenFour",callback,ping);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.PING,1);

        Write<?> write = capture.getWrites().getFirst();
        Assert.assertThat("Write.context",(String)write.context,is("TenFour"));
        Assert.assertSame("Write.callback",callback,write.callback);

        WebSocketFrame actual = write.frame;

        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.PING));
        Assert.assertThat("Frame.fin",actual.isFin(),is(true));
        Assert.assertThat("Frame.rsv1",actual.isRsv1(),is(false));
        Assert.assertThat("Frame.rsv2",actual.isRsv2(),is(false));
        Assert.assertThat("Frame.rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload,StringUtil.__UTF8_CHARSET);
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload",expected,actual.getPayload().slice());
    }
}
