package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.protocol.OpCode;

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

    private final EnumMap<OpCode, FrameGenerator> generators = new EnumMap<>(OpCode.class);
    private final FrameGenerator basicGenerator;

    public Generator(WebSocketPolicy policy)
    {
        generators.put(OpCode.CLOSE,new CloseFrameGenerator(policy));
        basicGenerator = new FrameGenerator(policy);
    }

    public ByteBuffer generate(ByteBuffer buffer, BaseFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("To Generate: {}",frame);
        }
        FrameGenerator generator = generators.get(frame.getOpCode());
        if (generator == null)
        {
            // no specific generator? use default
            generator = basicGenerator;
        }
        ByteBuffer ret = generator.generate(buffer,frame);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Generated[{}]: {}",generator.getClass().getSimpleName(),BufferUtil.toDetailString(buffer));
        }
        return ret;
    }

    @Override
    public String toString()
    {
        return String.format("Generator {%s registered}",generators.size());
    }

}
