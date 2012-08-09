package org.eclipse.jetty.websocket.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Process the payload (for demasking, validating, etc..)
 */
public interface PayloadProcessor
{
    /**
     * Used to process payloads for in the spec.
     * 
     * @param payload
     *            the payload to process
     * @throws BadPayloadException
     *             the exception when the payload fails to validate properly
     */
    public void process(ByteBuffer payload);

    public void reset(WebSocketFrame frame);
}
