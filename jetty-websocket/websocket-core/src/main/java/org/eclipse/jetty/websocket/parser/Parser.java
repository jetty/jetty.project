package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

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

    private static final Logger LOG = Log.getLogger(Parser.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final FrameParser parser;
    private WebSocketPolicy policy;

    public Parser(WebSocketPolicy wspolicy)
    {
        /*
         * TODO: Investigate addition of decompression factory similar to SPDY work in situation of negotiated deflate extension?
         */

        this.policy = wspolicy;
        this.parser = new FrameParser(wspolicy);
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
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
                if (parser.parse(buffer))
                {
                    notifyFrame(parser.getFrame());
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

    @Override
    public String toString()
    {
        return String.format("%s(parser=%s)",getClass().getSimpleName(),parser);
    }
}
