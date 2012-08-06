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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.extensions.identity.IdentityExtension;
import org.eclipse.jetty.websocket.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture.Write;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

public class IdentityExtensionTest
{
    /**
     * Verify that incoming frames are unmodified
     */
    @Test
    public void testIncomingFrames()
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        Extension ext = new IdentityExtension();
        ext.setNextIncomingFrames(capture);

        WebSocketFrame frame = WebSocketFrame.text("hello");
        ext.incoming(frame);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.TEXT,1);
        WebSocketFrame actual = capture.getFrames().getFirst();

        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.TEXT));
        Assert.assertThat("Frame.fin",actual.isFin(),is(true));
        Assert.assertThat("Frame.rsv1",actual.isRsv1(),is(false));
        Assert.assertThat("Frame.rsv2",actual.isRsv2(),is(false));
        Assert.assertThat("Frame.rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer("hello",StringUtil.__UTF8_CHARSET);
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload",expected,actual.getPayload().slice());
    }

    /**
     * Verify that outgoing frames are unmodified
     */
    @Test
    public void testOutgoingFrames() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        Extension ext = new IdentityExtension();
        ext.setNextOutgoingFrames(capture);

        WebSocketFrame frame = WebSocketFrame.text("hello");
        FutureCallback<String> callback = new FutureCallback<>();
        ext.output("WORLD",callback,frame);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.TEXT,1);

        Write<?> write = capture.getWrites().getFirst();
        Assert.assertThat("Write.context",(String)write.context,is("WORLD"));
        Assert.assertSame("Write.callback",callback,write.callback);

        WebSocketFrame actual = write.frame;

        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.TEXT));
        Assert.assertThat("Frame.fin",actual.isFin(),is(true));
        Assert.assertThat("Frame.rsv1",actual.isRsv1(),is(false));
        Assert.assertThat("Frame.rsv2",actual.isRsv2(),is(false));
        Assert.assertThat("Frame.rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer("hello",StringUtil.__UTF8_CHARSET);
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload",expected,actual.getPayload().slice());
    }

}
