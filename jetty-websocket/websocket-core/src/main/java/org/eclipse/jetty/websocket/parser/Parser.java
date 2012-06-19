package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.frames.BaseFrame;

/**
 * Parsing of a frame in WebSocket land.
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
public class Parser {
    public interface Listener extends EventListener
    {
        public void onFrame(final BaseFrame frame);
        public void onWebSocketException(WebSocketException e);
    }

    private enum State
    {
        FINOP,
        BASE_FRAMING,
        PAYLOAD
    }

    private static final Logger LOG = Log.getLogger(Parser.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private State state = State.FINOP;

    private final EnumMap<OpCode, FrameParser> parsers = new EnumMap<>(OpCode.class);

    private FrameParser parser;

    public Parser()
    {
        /*
         * TODO: Investigate addition of decompression factory similar to SPDY work in situation of negotiated deflate extension?
         */
        reset();

        parsers.put(OpCode.CONTINUATION,new ContinuationPayloadParser());
        parsers.put(OpCode.TEXT,new TextPayloadParser());
        parsers.put(OpCode.BINARY,new BinaryPayloadParser());
        parsers.put(OpCode.CLOSE,new ClosePayloadParser());
        parsers.put(OpCode.PING,new PingPayloadParser());
        parsers.put(OpCode.PONG,new PongPayloadParser());
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    protected void notifyFrame(final BaseFrame f)
    {
        LOG.debug("Notify Frame: {}",f);
        for (Listener listener : listeners)
        {
            try
            {
                listener.onFrame(f);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
    }

    protected void notifyWebSocketException(WebSocketException e)
    {
        LOG.debug(e);
        for (Listener listener : listeners)
        {
            listener.onWebSocketException(e);
        }
    }

    public void parse(ByteBuffer buffer)
    {
        try {
            LOG.debug("Parsing {} bytes",buffer.remaining());
            while (buffer.hasRemaining())
            {
                switch (state)
                {
                    case FINOP:
                    {
                        // peek at byte
                        byte b = buffer.get();
                        byte flags = (byte)(0xF & (b >> 4));
                        boolean fin = ((flags & BaseFrame.FLAG_FIN) == 1);
                        boolean rsv1 = ((flags & BaseFrame.FLAG_RSV1) == 1);
                        boolean rsv2 = ((flags & BaseFrame.FLAG_RSV2) == 1);
                        boolean rsv3 = ((flags & BaseFrame.FLAG_RSV3) == 1);
                        OpCode opcode = OpCode.from((byte)(b & 0xF));

                        if (opcode.isControlFrame() && !fin)
                        {
                            throw new WebSocketException("Fragmented Control Frame");
                        }

                        if (parser == null)
                        {
                            // Establish specific type parser and hand off to them.
                            parser = parsers.get(opcode);
                            parser.reset();
                            parser.initFrame(fin,rsv1,rsv2,rsv3,opcode);
                        }

                        state = State.BASE_FRAMING;
                        break;
                    }
                    case BASE_FRAMING:
                    {
                        if (parser.parseBaseFraming(buffer))
                        {
                            state = State.PAYLOAD;
                        }
                        break;
                    }
                    case PAYLOAD:
                    {
                        if (parser.parsePayload(buffer))
                        {
                            notifyFrame(parser.getFrame());
                            reset();
                        }
                        break;
                    }
                }
            }
        }
        catch (WebSocketException e)
        {
            notifyWebSocketException(e);
        } catch(Throwable t) {
            notifyWebSocketException(new WebSocketException(t));
        }
        finally {
            // Be sure to consume after exceptions
            buffer.position(buffer.limit());
        }
    }

    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    public void reset()
    {
        state = State.FINOP;
        parser.reset();
        parser = null;
    }
}
