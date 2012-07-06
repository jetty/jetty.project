package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.callbacks.WebSocketCloseCallback;
import org.eclipse.jetty.websocket.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.FrameGenerator;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link AsyncConnection} framework of jetty-io
 */
public class WebSocketAsyncConnection extends AbstractAsyncConnection implements RawConnection, WebSocketConnection
{
    private static final Logger LOG = Log.getLogger(WebSocketAsyncConnection.class);
    private static final ThreadLocal<WebSocketAsyncConnection> CURRENT_CONNECTION = new ThreadLocal<WebSocketAsyncConnection>();

    public static WebSocketAsyncConnection getCurrentConnection()
    {
        return CURRENT_CONNECTION.get();
    }

    protected static void setCurrentConnection(WebSocketAsyncConnection connection)
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

    public WebSocketAsyncConnection(AsyncEndPoint endp, Executor executor, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor);
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy);
        this.parser = new Parser(policy);
        this.extensions = new ArrayList<>();
    }

    @Override
    public void close() throws IOException
    {
        terminateConnection(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason) throws IOException
    {
        terminateConnection(statusCode,reason);
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

    @Override
    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    @Override
    public Executor getExecutor()
    {
        return getExecutor();
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

    public Parser getParser()
    {
        return parser;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return getEndPoint().getRemoteAddress();
    }

    @Override
    public String getSubProtocol()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return getEndPoint().isOpen();
    }

    @Override
    public void onClose()
    {
        LOG.debug("onClose()");
        super.onClose();
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

    @Override
    public void onOpen()
    {
        LOG.debug("onOpen()");
        super.onOpen();
        fillInterested();
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
     *            the (optional) reason string. (null is allowed)
     * @see StatusCode
     */
    private void terminateConnection(int statusCode, String reason)
    {
        CloseFrame close = new CloseFrame(statusCode,reason);

        // fire and forget -> close frame
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clearToFill(buf);
        generator.generate(buf,close);
        BufferUtil.flipToFlush(buf,0);
        getEndPoint().write(null,new WebSocketCloseCallback(this,buf),buf);
    }

    @Override
    public String toString()
    {
        return String.format("%s{g=%s,p=%s}",super.toString(),generator,parser);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, byte buf[], int offset, int len) throws IOException
    {
        if (len == 0)
        {
            // nothing to write
            return;
        }
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},byte[],{},{})",callback,offset,len);
        }
        ByteBuffer raw = bufferPool.acquire(len + FrameGenerator.OVERHEAD,false);
        BufferUtil.clearToFill(raw);
        BinaryFrame frame = new BinaryFrame(buf,offset,len);
        frame.setFin(true);
        generator.generate(raw,frame);
        BufferUtil.flipToFlush(raw,0);
        writeRaw(context,callback,raw);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, String... messages) throws IOException
    {
        int len = messages.length;
        if (len == 0)
        {
            // nothing to write
            return;
        }
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},Strings->{})",callback,messages.length);
        }
        ByteBuffer raw[] = new ByteBuffer[messages.length];
        for (int i = 0; i < len; i++)
        {
            TextFrame frame = new TextFrame(messages[i]);
            frame.setFin(true);
            raw[i] = bufferPool.acquire(policy.getBufferSize(),false);
            BufferUtil.clear(raw[i]);
            generator.generate(raw[i],frame);
            BufferUtil.flipToFlush(raw[i],0);
        }
        writeRaw(context,callback,raw);
    }

    @Override
    public <C> void writeRaw(C context, Callback<C> callback, ByteBuffer... buf) throws IOException
    {
        getEndPoint().write(context,callback,buf);
    }
}
