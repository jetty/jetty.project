package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.server.callbacks.WebSocketCloseCallback;

// TODO: implement WebSocket.Connection (for API access)?
public class AsyncWebSocketConnection extends AbstractAsyncConnection
{
    private static final Logger LOG = Log.getLogger(AsyncWebSocketConnection.class);
    private static final ThreadLocal<AsyncWebSocketConnection> CURRENT_CONNECTION = new ThreadLocal<AsyncWebSocketConnection>();

    public static AsyncWebSocketConnection getCurrentConnection()
    {
        return CURRENT_CONNECTION.get();
    }

    protected static void setCurrentConnection(AsyncWebSocketConnection connection)
    {
        CURRENT_CONNECTION.set(connection);
    }

    private final ByteBufferPool bufferPool;
    private Generator generator;
    private Parser parser;
    private WebSocketPolicy policy;
    // TODO: track extensions? (only those that need to operate at this level?)
    // TODO: are extensions going to layer the endpoint?
    // TODO: are extensions going to layer the connection?
    private List<ExtensionConfig> extensions;

    public AsyncWebSocketConnection(AsyncEndPoint endp, Executor executor, WebSocketPolicy policy)
    {
        super(endp,executor);
        this.policy = policy;
        this.bufferPool = new StandardByteBufferPool(policy.getBufferSize());
        this.generator = new Generator(policy);
        this.parser = new Parser(policy);
        this.extensions = new ArrayList<>();
    }

    private int fill(AsyncEndPoint endPoint, ByteBuffer buffer)
    {
        try
        {
            return endPoint.fill(buffer);
        }
        catch (IOException e)
        {
            terminateConnection(StatusCode.PROTOCOL,e.getMessage());
            throw new RuntimeIOException(e);
        }
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    /**
     * Get the list of extensions in use.
     * <p>
     * This list is negotiated during the WebSocket Upgrade Request/Response handshake.
     * 
     * @return the list of negotiated extensions in use.
     */
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public void onFillable()
    {
        LOG.debug("onFillable");
        setCurrentConnection(this);
        ByteBuffer buffer = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clear(buffer);
        try
        {
            read(buffer);
        }
        finally
        {
            setCurrentConnection(null);
            bufferPool.release(buffer);
        }
    }

    private void read(ByteBuffer buffer)
    {
        while (true)
        {
            int filled = fill(getEndPoint(),buffer);
            if (filled == 0)
            {
                break;
            }
            if (filled < 0)
            {
                // IO error
                terminateConnection(StatusCode.PROTOCOL,null);
                break;
            }
            parser.parse(buffer);
        }
    }

    /**
     * Get the list of extensions in use.
     * <p>
     * This list is negotiated during the WebSocket Upgrade Request/Response handshake.
     * 
     * @param extensions
     *            the list of negotiated extensions in use.
     */
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        this.extensions = extensions;
    }

    /**
     * For terminating connections forcefully.
     * 
     * @param statusCode
     *            the WebSocket status code.
     * @param reason
     *            the (optiona) reason string. (null is allowed)
     * @see StatusCode
     */
    private void terminateConnection(short statusCode, String reason)
    {
        CloseFrame close = new CloseFrame(statusCode);
        close.setReason(reason);

        // fire and forget -> close frame
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        generator.generate(buf,close);
        getEndPoint().write(null,new WebSocketCloseCallback(this,buf),buf);
    }
}
