//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.UnitGenerator;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.io.payload.DeMaskProcessor;
import org.junit.Test;

public class DeMaskProcessorTest
{
    private static final Logger LOG = Log.getLogger(DeMaskProcessorTest.class);

    @Test
    public void testDeMaskText()
    {
        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        WebSocketFrame frame = WebSocketFrame.text(message);
        frame.setMask(TypeUtil.fromHexString("11223344"));
        // frame.setMask(TypeUtil.fromHexString("00000000"));

        ByteBuffer buf = new UnitGenerator().generate(frame);
        LOG.debug("Buf: {}",BufferUtil.toDetailString(buf));
        ByteBuffer payload = buf.slice();
        payload.position(6); // where payload starts
        LOG.debug("Payload: {}",BufferUtil.toDetailString(payload));

        DeMaskProcessor demask = new DeMaskProcessor();
        demask.reset(frame);
        demask.process(payload);

        ByteBufferAssert.assertEquals("DeMasked Text Payload",message,payload);
    }
}
