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
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;

/**
 * Parsing of a frames in WebSocket land.
 */
public class Parser
{
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
    private final EnumMap<OpCode, FrameParser<?>> parsers = new EnumMap<>(OpCode.class);
    private FrameParser<?> parser;
    private WebSocketPolicy policy;
    private State state = State.FINOP;

    public Parser(WebSocketPolicy wspolicy)
    {
        /*
         * TODO: Investigate addition of decompression factory similar to SPDY work in situation of negotiated deflate extension?
         */

        this.policy = wspolicy;

        reset();

        parsers.put(OpCode.TEXT,new TextPayloadParser(policy));
        parsers.put(OpCode.BINARY,new BinaryPayloadParser(policy));
        parsers.put(OpCode.CLOSE,new ClosePayloadParser(policy));
        parsers.put(OpCode.PING,new PingPayloadParser(policy));
        parsers.put(OpCode.PONG,new PongPayloadParser(policy));
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
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
        try
        {
            LOG.debug("Parsing {} bytes",buffer.remaining());
            while (buffer.hasRemaining())
            {
                switch (state)
                {
                    case FINOP:
                    {
                        // peek at byte
                        byte b = buffer.get();
                        boolean fin = ((b & 0x80) != 0);
                        boolean rsv1 = ((b & 0x40) != 0);
                        boolean rsv2 = ((b & 0x20) != 0);
                        boolean rsv3 = ((b & 0x10) != 0);
                        byte opc = (byte)(b & 0x0F);
                        OpCode opcode = OpCode.from(opc);

                        if (opcode == null)
                        {
                            throw new WebSocketException("Unknown opcode: " + opc);
                        }

                        if (opcode.isControlFrame() && !fin)
                        {
                            throw new WebSocketException("Fragmented Control Frame [" + opcode.name() + "]");
                        }

                        if (opcode == OpCode.CONTINUATION)
                        {
                            if (parser == null)
                            {
                                throw new WebSocketException("Fragment continuation frame without prior !FIN");
                            }
                        }

                        if (parser == null)
                        {
                            // Establish specific type parser and hand off to them.
                            parser = parsers.get(opcode);
                        }
                        parser.reset();
                        parser.initFrame(fin,rsv1,rsv2,rsv3,opcode);

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
                            parser.reset();
                            if (parser.getFrame().isFin())
                            {
                                reset();
                            }
                            state = State.FINOP;
                        }
                        break;
                    }
                }
            }
        }
        catch (WebSocketException e)
        {
            notifyWebSocketException(e);
        }
        catch (Throwable t)
        {
            notifyWebSocketException(new WebSocketException(t));
        }
        finally
        {
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
        if (parser != null)
        {
            parser.reset();
        }
        parser = null;
    }
}
