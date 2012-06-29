package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.FrameGenerator;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.server.callbacks.WebSocketCloseCallback;

// TODO: implement WebSocket.Connection (for API access)?
public class AsyncWebSocketConnection extends AbstractAsyncConnection implements WebSocketConnection
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

    @Override
    public void close() throws IOException
    {
        write(new CloseFrame(StatusCode.NORMAL));
    }

    @Override
    public void close(int statusCode, String reason) throws IOException
    {
        write(new CloseFrame(statusCode,reason));
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
        CloseFrame close = new CloseFrame(statusCode,reason);

        // fire and forget -> close frame
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        generator.generate(buf,close);
        getEndPoint().write(null,new WebSocketCloseCallback(this,buf),buf);
    }

    @Override
    public String toString()
    {
        return String.format("%s{generator=%s,parser=%s}",super.toString(),generator,parser);
    }

    @Override
    public void write(BaseFrame frame) throws IOException
    {
        if (frame == null)
        {
            // nothing to write
            return;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(BaseFrame->{})",frame);
        }

        ByteBuffer raw = ByteBuffer.allocate(frame.getPayloadLength() + FrameGenerator.OVERHEAD);
        generator.generate(raw,frame);
        Callback<Void> nop = new FutureCallback<>(); // TODO: add buffer release callback?
        getEndPoint().write(null,nop,raw);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(byte[]->{})",data);
        }
        write(new BinaryFrame(data,offset,length));
    }

    @Override
    public void write(ByteBuffer... buffers) throws IOException
    {
        int len = buffers.length;
        if (len == 0)
        {
            // nothing to write
            return;
        }
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(ByteBuffers->{})",buffers.length);
        }
        ByteBuffer raw[] = new ByteBuffer[len];
        for (int i = 0; i < len; i++)
        {
            raw[i] = bufferPool.acquire(buffers[i].remaining() + FrameGenerator.OVERHEAD,false);
            BinaryFrame frame = new BinaryFrame(buffers[i]);
            generator.generate(raw[i],frame);
        }
        Callback<Void> nop = new FutureCallback<>(); // TODO: add buffer release callback?
        getEndPoint().write(null,nop,raw);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, BaseFrame... frames) throws IOException
    {
        int len = frames.length;
        if (len == 0)
        {
            // nothing to write
            return;
        }
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},BaseFrames->{})",callback,frames.length);
        }
        ByteBuffer raw[] = new ByteBuffer[len];
        for (int i = 0; i < len; i++)
        {
            raw[i] = bufferPool.acquire(frames[i].getPayloadLength() + FrameGenerator.OVERHEAD,false);
            generator.generate(raw[i],frames[i]);
        }
        getEndPoint().write(context,callback,raw);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IOException
    {
        int len = buffers.length;
        if (len == 0)
        {
            // nothing to write
            return;
        }
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},ByteBuffers->{})",callback,buffers.length);
        }
        ByteBuffer raw[] = new ByteBuffer[len];
        for (int i = 0; i < len; i++)
        {
            raw[i] = bufferPool.acquire(buffers[i].remaining() + FrameGenerator.OVERHEAD,false);
            BinaryFrame frame = new BinaryFrame(buffers[i]);
            generator.generate(raw[i],frame);
        }
        getEndPoint().write(context,callback,raw);
    }

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
        TextFrame frames[] = new TextFrame[len];
        for (int i = 0; i < len; i++)
        {
            frames[i] = new TextFrame(messages[i]);
        }
        write(context,callback,frames);
    }

    @Override
    public void write(String message) throws IOException
    {
        if (message == null)
        {
            // nothing to write
            return;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(String->{})",message);
        }
        write(new TextFrame(message));
    }
}
