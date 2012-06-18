package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.frames.ControlFrame;

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
public class Generator {

    private final EnumMap<OpCode, ControlFrameGenerator> generators = new EnumMap<>(OpCode.class);


    public Generator(ByteBufferPool bufferPool) //, CompressionFactory.Compressor compressor)
    {
        HeadersBlockGenerator headerBlockGenerator = new HeadersBlockGenerator();
        generators.put(OpCode.PING,new PingFrameGenerator());
        generators.put(OpCode.PONG,new PongFrameGenerator());
        generators.put(OpCode.CLOSE,new CloseFrameGenerator());

    }


    public ByteBuffer control(ControlFrame frame)
    {
        ControlFrameGenerator generator = generators.get(frame.getOpCode());
        return generator.generate(frame);
    }


}
