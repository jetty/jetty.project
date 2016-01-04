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

package org.eclipse.jetty.websocket.common.io.payload;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.UnitGenerator;
import org.eclipse.jetty.websocket.common.util.Hex;
import org.junit.Assert;
import org.junit.Test;

public class DeMaskProcessorTest
{
    private static final Logger LOG = Log.getLogger(DeMaskProcessorTest.class);

    @Test
    public void testDeMaskText()
    {
        // Use a string that is not multiple of 4 in length to test if/else branches in DeMaskProcessor
        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF01";

        WebSocketFrame frame = new TextFrame().setPayload(message);
        frame.setMask(TypeUtil.fromHexString("11223344"));

        ByteBuffer buf = UnitGenerator.generate(frame);
        LOG.debug("Buf: {}",BufferUtil.toDetailString(buf));
        ByteBuffer payload = buf.slice();
        payload.position(6); // where payload starts
        LOG.debug("Payload: {}",BufferUtil.toDetailString(payload));

        DeMaskProcessor demask = new DeMaskProcessor();
        demask.reset(frame);
        demask.process(payload);

        ByteBufferAssert.assertEquals("DeMasked Text Payload",message,payload);
    }

    @Test
    public void testDeMaskTextSliced()
    {
        final byte msgChar = '*';
        final int messageSize = 25;

        byte message[] = new byte[messageSize];
        Arrays.fill(message,msgChar);

        TextFrame frame = new TextFrame();
        frame.setPayload(ByteBuffer.wrap(message));
        frame.setMask(Hex.asByteArray("11223344"));

        ByteBuffer buf = UnitGenerator.generate(frame);
        LOG.debug("Buf: {}",BufferUtil.toDetailString(buf));
        ByteBuffer payload = buf.slice();
        payload.position(6); // where payload starts
        
        LOG.debug("Payload: {}",BufferUtil.toDetailString(payload));
        LOG.debug("Pre-Processed: {}",Hex.asHex(payload));

        DeMaskProcessor demask = new DeMaskProcessor();
        demask.reset(frame);
        ByteBuffer slice1 = payload.slice();
        ByteBuffer slice2 = payload.slice();

        // slice at non-multiple of 4, but also where last buffer remaining
        // is more than 4.
        int slicePoint = 7;
        slice1.limit(slicePoint);
        slice2.position(slicePoint);

        Assert.assertThat("Slices are setup right",slice1.remaining() + slice2.remaining(),is(messageSize));

        demask.process(slice1);
        demask.process(slice2);
        
        LOG.debug("Post-Processed: {}",Hex.asHex(payload));

        Assert.assertThat("Payload.remaining",payload.remaining(),is(messageSize));
        for (int i = payload.position(); i < payload.limit(); i++)
        {
            Assert.assertThat("payload[" + i + "]",payload.get(i),is(msgChar));
        }
    }
}
