package org.eclipse.jetty.websocket.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.protocol.UnitGenerator;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
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
