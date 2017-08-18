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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CloseStatusTest
{
    /**
     * A test where no close is provided
     */
    @Test
    public void testAnonymousClose()
    {
        CloseStatus close = new CloseStatus();
        assertThat("close.code",close.getCode(), Matchers.is(WSConstants.NO_CODE));
        assertThat("close.reason",close.getReason(),nullValue());

        CloseFrame frame = new CloseFrame().setPayload(close);
        assertThat("close frame op code",frame.getOpCode(), Matchers.is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload",frame.hasPayload(),is(false));
        assertThat("close frame payload length",frame.getPayloadLength(),is(0));
    }
    
    /**
     * A test where NO_CODE (1005) is provided
     */
    @Test
    public void testNoCode()
    {
        CloseStatus close = new CloseStatus(WSConstants.NO_CODE);
        assertThat("close.code",close.getCode(), Matchers.is(WSConstants.NO_CODE));
        assertThat("close.reason",close.getReason(),nullValue());

        CloseFrame frame = new CloseFrame().setPayload(close);
        assertThat("close frame op code",frame.getOpCode(),is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload",frame.hasPayload(),is(false));
        assertThat("close frame payload length",frame.getPayloadLength(),is(0));
    }
    
    /**
     * A test where NO_CLOSE (1006) is provided
     */
    @Test
    public void testNoClose()
    {
        CloseStatus close = new CloseStatus(WSConstants.NO_CLOSE);
        assertThat("close.code",close.getCode(), Matchers.is(WSConstants.NO_CLOSE));
        assertThat("close.reason",close.getReason(),nullValue());

        CloseFrame frame = new CloseFrame().setPayload(close);
        assertThat("close frame op code",frame.getOpCode(),is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload",frame.hasPayload(),is(false));
        assertThat("close frame payload length",frame.getPayloadLength(),is(0));
    }

    /**
     * A test of FAILED_TLS_HANDSHAKE (1007)
     */
    @Test
    public void testFailedTlsHandshake()
    {
        CloseStatus close = new CloseStatus(WSConstants.FAILED_TLS_HANDSHAKE);
        assertThat("close.code",close.getCode(), Matchers.is(WSConstants.FAILED_TLS_HANDSHAKE));
        assertThat("close.reason",close.getReason(),nullValue());

        CloseFrame frame = new CloseFrame().setPayload(close);
        assertThat("close frame op code",frame.getOpCode(),is(OpCode.CLOSE));
        // should result in no payload
        assertThat("close frame has payload",frame.hasPayload(),is(false));
        assertThat("close frame payload length",frame.getPayloadLength(),is(0));
    }

    /**
     * A test of NORMAL (1000)
     */
    @Test
    public void testNormal()
    {
        CloseStatus close = new CloseStatus(WSConstants.NORMAL);
        assertThat("close.code",close.getCode(), Matchers.is(WSConstants.NORMAL));
        assertThat("close.reason",close.getReason(),nullValue());

        CloseFrame frame = new CloseFrame().setPayload(close);
        assertThat("close frame op code",frame.getOpCode(),is(OpCode.CLOSE));
        assertThat("close frame payload length",frame.getPayloadLength(),is(2));
    }
    
    private ByteBuffer asByteBuffer(int statusCode, String reason)
    {
        int len = 2; // status code length
        byte utf[] = null;
        if (StringUtil.isNotBlank(reason))
        {
            utf = StringUtil.getUtf8Bytes(reason);
            len += utf.length;
        }

        ByteBuffer buf = BufferUtil.allocate(len);
        BufferUtil.flipToFill(buf);
        buf.put((byte)((statusCode >>> 8) & 0xFF));
        buf.put((byte)((statusCode >>> 0) & 0xFF));

        if (utf != null)
        {
            buf.put(utf,0,utf.length);
        }
        BufferUtil.flipToFlush(buf,0);
        
        return buf;
    }
    
    @Test
    public void testFromFrame()
    {
        ByteBuffer payload = asByteBuffer(WSConstants.NORMAL,null);
        assertThat("payload length", payload.remaining(), is(2));
        CloseFrame frame = new CloseFrame();
        frame.setPayload(payload);
        
        // create from frame
        CloseStatus close = frame.getCloseStatus();
        assertThat("close.code",close.getCode(), Matchers.is(WSConstants.NORMAL));
        assertThat("close.reason",close.getReason(),nullValue());

        // and back again
        frame = new CloseFrame().setPayload(close);
        assertThat("close frame op code",frame.getOpCode(),is(OpCode.CLOSE));
        assertThat("close frame payload length",frame.getPayloadLength(),is(2));
    }
}
