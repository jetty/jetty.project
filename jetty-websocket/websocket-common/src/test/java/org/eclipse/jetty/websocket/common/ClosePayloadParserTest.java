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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.eclipse.jetty.websocket.common.util.MaskedByteBuffer;
import org.junit.Assert;
import org.junit.Test;

public class ClosePayloadParserTest
{
    @Test
    public void testGameOver()
    {
        String expectedReason = "Game Over";

        byte utf[] = expectedReason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(utf.length + 2);
        payload.putChar((char)StatusCode.NORMAL);
        payload.put(utf,0,utf.length);
        payload.flip();

        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)(0x80 | OpCode.CLOSE)); // fin + close
        buf.put((byte)(0x80 | payload.remaining()));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,payload);
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);
        CloseInfo close = new CloseInfo(capture.getFrames().poll());
        Assert.assertThat("CloseFrame.statusCode",close.getStatusCode(),is(StatusCode.NORMAL));
        Assert.assertThat("CloseFrame.data",close.getReason(),is(expectedReason));
    }
}
