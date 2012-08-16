package org.eclipse.jetty.websocket.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Binary payload validator does nothing, essentially.
 */
public class BinaryValidator implements PayloadProcessor
{
    public static final BinaryValidator INSTANCE = new BinaryValidator();

    @Override
    public void process(ByteBuffer payload)
    {
        /* all payloads are valid in this case */
    }

    @Override
    public void reset(WebSocketFrame frame)
    {
        /* do nothing */
    }
}
