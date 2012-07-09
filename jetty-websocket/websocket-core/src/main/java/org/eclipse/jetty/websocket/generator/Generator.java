package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Generating a frame in WebSocket land.
 * 
 * <pre>
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *   +-+-+-+-+-------+-+-------------+-------------------------------+
 *   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *   | |1|2|3|       |K|             |                               |
 *   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *   |     Extended payload length continued, if payload len == 127  |
 *   + - - - - - - - - - - - - - - - +-------------------------------+
 *   |                               |Masking-key, if MASK set to 1  |
 *   +-------------------------------+-------------------------------+
 *   | Masking-key (continued)       |          Payload Data         |
 *   +-------------------------------- - - - - - - - - - - - - - - - +
 *   :                     Payload Data continued ...                :
 *   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *   |                     Payload Data continued ...                |
 *   +---------------------------------------------------------------+
 * </pre>
 */
public class Generator
{
    private static final Logger LOG = Log.getLogger(Generator.class);

    private final FrameGenerator basicGenerator;

    public Generator(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        basicGenerator = new FrameGenerator(policy,bufferPool);
    }

    public ByteBuffer generate(ByteBuffer buffer, WebSocketFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("To Generate: {}",frame);
        }
        ByteBuffer ret = basicGenerator.generate(buffer,frame);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Generated[{}]: {}",basicGenerator.getClass().getSimpleName(),BufferUtil.toDetailString(buffer));
        }
        return ret;
    }

    public ByteBuffer generate(int size, WebSocketFrame frame)
    {
        // NEW ONE - allocate and fill only to param size
        // note: size is the buffer size to generate into, not related to the payload size
        // note: size cannot be less than framing overhead
        // TODO: update frame.setAvailable() to indicate how much of payload is left to be generated
        return basicGenerator.generate(frame);
    }

    public ByteBuffer generate(WebSocketFrame frame)
    {
        // NEW ONE - allocate as much as needed
        return basicGenerator.generate(frame);
    }

    @Override
    public String toString()
    {
        return String.format("Generator [basic=%s]",basicGenerator.getClass().getSimpleName());
    }

}
