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

package org.eclipse.jetty.websocket.core;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.core.io.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.core.io.WSRemoteImpl;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WSRemoteEndpointTest
{
    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule(WSRemoteEndpointTest.class);

    private WSRemoteEndpoint newWSRemote(OutgoingFramesCapture framesCapture)
    {
        WSRemoteImpl remote = new WSRemoteImpl(framesCapture);
        remote.open();
        return remote;
    }

    @Test
    public void testTextBinaryText() throws Exception
    {
        OutgoingFramesCapture framesCapture = new OutgoingFramesCapture();
        WSRemoteEndpoint remote = newWSRemote(framesCapture);

        // Start text message
        remote.sendPartialText("Hello ", false, Callback.NOOP);

        try
        {
            // Attempt to start Binary Message
            ByteBuffer bytes = ByteBuffer.wrap(new byte[]{0, 1, 2});
            remote.sendPartialBinary(bytes, false, Callback.NOOP);
            Assert.fail("Expected " + IllegalStateException.class.getName());
        }
        catch (IllegalStateException e)
        {
            // Expected path
            assertThat("Exception", e.getMessage(), containsString("Cannot send"));
        }

        // End text message
        remote.sendPartialText("World!", true, Callback.NOOP);

        framesCapture.assertFrameCount(2);

        WSFrame actual;

        actual = framesCapture.frames.remove();
        assertThat("Frame[0].opCode", actual.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame[0].fin", actual.isFin(), is(false));
        assertThat("Frame[0].payload", actual.getPayloadAsUTF8(), is("Hello "));

        actual = framesCapture.frames.remove();
        assertThat("Frame[1].opCode", actual.getOpCode(), is(OpCode.CONTINUATION));
        assertThat("Frame[1].fin", actual.isFin(), is(true));
        assertThat("Frame[1].payload", actual.getPayloadAsUTF8(), is("World!"));
    }

    @Test
    public void testTextPingText() throws Exception
    {
        OutgoingFramesCapture framesCapture = new OutgoingFramesCapture();
        WSRemoteEndpoint remote = newWSRemote(framesCapture);

        // Start text message
        remote.sendPartialText("Hello ", false, Callback.NOOP);

        // Attempt to send Ping Message
        remote.sendPing(ByteBuffer.wrap(new byte[] {0}), Callback.NOOP);

        // End text message
        remote.sendPartialText("World!", true, Callback.NOOP);

        framesCapture.assertFrameCount(3);

        WSFrame actual;

        actual = framesCapture.frames.remove();
        assertThat("Frame[0].opCode", actual.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame[0].fin", actual.isFin(), is(false));
        assertThat("Frame[0].payload", actual.getPayloadAsUTF8(), is("Hello "));

        actual = framesCapture.frames.remove();
        assertThat("Frame[1].opCode", actual.getOpCode(), is(OpCode.PING));
        assertThat("Frame[1].fin", actual.isFin(), is(true));
        assertThat("Frame[1].payload", actual.getPayload(), is(ByteBuffer.wrap(new byte[] {0})));

        actual = framesCapture.frames.remove();
        assertThat("Frame[2].opCode", actual.getOpCode(), is(OpCode.CONTINUATION));
        assertThat("Frame[2].fin", actual.isFin(), is(true));
        assertThat("Frame[2].payload", actual.getPayloadAsUTF8(), is("World!"));
    }
}
