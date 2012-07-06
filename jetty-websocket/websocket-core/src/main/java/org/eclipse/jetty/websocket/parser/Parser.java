package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.util.CloseUtil;

/**
 * Parsing of a frames in WebSocket land.
 */
public class Parser
{
    public interface Listener extends EventListener
    {
        public void onFrame(final WebSocketFrame frame);

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
    private final FrameParser parser;
    private WebSocketPolicy policy;
    private State state = State.FINOP;

    public Parser(WebSocketPolicy wspolicy)
    {
        /*
         * TODO: Investigate addition of decompression factory similar to SPDY work in situation of negotiated deflate extension?
         */

        this.policy = wspolicy;
        this.parser = new FrameParser(wspolicy);
        reset();
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    private void assertValidClose()
    {
        CloseUtil.assertValidPayload(parser.getFrame());
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    protected void notifyFrame(final WebSocketFrame f)
    {
        LOG.debug("Notify Frame: {}",f);
        for (Listener listener : listeners)
        {
            try
            {
                listener.onFrame(f);
            }
            catch (WebSocketException e)
            {
                notifyWebSocketException(e);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
                notifyWebSocketException(new WebSocketException(t));
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

                        LOG.debug("OpCode {}, fin={}",opcode.name(),fin);

                        if (opcode.isControlFrame() && !fin)
                        {
                            throw new ProtocolException("Fragmented Control Frame [" + opcode.name() + "]");
                        }

                        if (opcode == OpCode.CONTINUATION)
                        {
                            if (parser.getFrame() == null)
                            {
                                throw new ProtocolException("Fragment continuation frame without prior !FIN");
                            }
                            // Be careful to use the original opcode
                            opcode = parser.getFrame().getOpCode();
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
                            // special check for close
                            if (parser.getFrame().getOpCode() == OpCode.CLOSE)
                            {
                                assertValidClose();
                            }
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

            /*
             * if the payload was empty we could end up in this state because there was no remaining bits to process
             */
            if (state == State.PAYLOAD)
            {
                notifyFrame(parser.getFrame());
                parser.reset();
                if (parser.getFrame().isFin())
                {
                    reset();
                }
                state = State.FINOP;
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
        parser.reset();
    }

    @Override
    public String toString()
    {
        return String.format("%s(state=%s, parser=%s)", getClass().getSimpleName(), state, parser);
    }
}
