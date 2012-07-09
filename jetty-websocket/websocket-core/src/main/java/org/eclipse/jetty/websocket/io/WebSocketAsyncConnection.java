package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.callbacks.WebSocketCloseCallback;
import org.eclipse.jetty.websocket.generator.FrameGenerator;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link AsyncConnection} framework of jetty-io
 */
public class WebSocketAsyncConnection extends AbstractAsyncConnection implements RawConnection, WebSocketConnection
{
    public class ControlFrameBytes<C> extends FrameBytes<C>
    {
        public ControlFrameBytes(C context, Callback<C> callback, WebSocketFrame frame, ByteBuffer buffer)
        {
            super(context,callback,frame,buffer);
        }

        @Override
        public void completed(C context) {
            bufferPool.release(buffer);

            super.completed(context);

            if(frame.getOpCode() == OpCode.CLOSE)
            {
                // TODO: close the connection (no packet)
            }
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return buffer;
        }
    }

    public class DataFrameBytes<C> extends FrameBytes<C>
    {
        private int size;

        public DataFrameBytes(C context, Callback<C> callback, WebSocketFrame frame, ByteBuffer buffer)
        {
            super(context,callback,frame,buffer);
        }

        @Override
        public void completed(C context)
        {
            bufferPool.release(buffer);

            if (frame.remaining() > 0)
            {
                // We have written a frame out of this DataInfo, but there is more to write.
                // We need to keep the correct ordering of frames, to avoid that another
                // DataInfo for the same stream is written before this one is finished.
                prepend(this);
            }
            else
            {
                super.completed(context);
            }
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            try
            {
                int windowSize = policy.getBufferSize();
                // TODO: create a window size?

                size = frame.getPayloadLength();
                if (size > windowSize)
                {
                    size = windowSize;
                }

                buffer = generator.generate(size,frame);
                return buffer;
            }
            catch (Throwable x)
            {
                failed(context,x);
                return null;
            }
        }
    }

    public abstract class FrameBytes<C> implements Callback<C>, Runnable
    {
        private final Callback<C> callback;
        protected final C context;
        protected final WebSocketFrame frame;
        protected ByteBuffer buffer;
        // Task used to timeout the bytes
        protected volatile ScheduledFuture<?> task;

        protected FrameBytes(C context, Callback<C> callback, WebSocketFrame frame, ByteBuffer buffer)
        {
            this.callback = callback;
            this.context = context;
            this.frame = frame;
            this.buffer = buffer;
        }

        private void cancelTask()
        {
            ScheduledFuture<?> task = this.task;
            if (task != null)
            {
                task.cancel(false);
            }
        }

        @Override
        public void completed(C context)
        {
            cancelTask();
            callback.completed(context);
        }

        @Override
        public void failed(C context, Throwable x)
        {
            cancelTask();
            callback.failed(context,x);
        }

        public abstract ByteBuffer getByteBuffer();

        @Override
        public void run()
        {
            // If this occurs we had a timeout!
            try
            {
                close();
            }
            catch (IOException e)
            {
                LOG.ignore(e);
            }
            failed(context, new InterruptedByTimeoutException());
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

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
    private final ScheduledExecutorService scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;

    // TODO: track extensions? (only those that need to operate at this level?)
    // TODO: are extensions going to layer the endpoint?
    // TODO: are extensions going to layer the connection?
    private List<ExtensionConfig> extensions;

    public WebSocketAsyncConnection(AsyncEndPoint endp, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor);
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy);
        this.scheduler = scheduler;
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
            return 0;
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
        BufferUtil.clearToFill(buffer);
        try
        {
            read(buffer);
        }
        finally
        {
            // TODO: does fillInterested need to be called again?
            fillInterested();
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

    @Override
    public <C> void ping(C context, Callback<C> callback, byte[] payload) throws IOException
    {
        WebSocketFrame frame = FrameBuilder.ping().payload(payload).asFrame();
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        generator.generate(buf,frame);
        writeRaw(context,callback,buf);
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
        WebSocketFrame close = FrameBuilder.close(statusCode,reason).asFrame();

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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},byte[],{},{})",callback,offset,len);
        }
        if (len == 0)
        {
            // nothing to write
            return;
        }
        ByteBuffer raw = bufferPool.acquire(len + FrameGenerator.OVERHEAD,false);
        BufferUtil.clearToFill(raw);

        WebSocketFrame frame = FrameBuilder.binary(buf,offset,len).fin(true).asFrame();
        generator.generate(raw,frame);
        BufferUtil.flipToFlush(raw,0);
        writeRaw(context,callback,raw);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, String message) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},message.length:{})",callback,message.length());
        }

        WebSocketFrame frame = FrameBuilder.text(message).fin(true).asFrame();
        FrameBytes bytes = new FrameBytes<C>(callback,context,frame,buffer);

        WebSocketFrame frame = FrameBuilder.text(messages[i]).fin(true).asFrame();

        raw[i] = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clearToFill(raw[i]);
        generator.generate(raw[i],frame);
        BufferUtil.flipToFlush(raw[i],0);
        writeRaw(context,callback,raw);
    }

    @Override
    public <C> void writeRaw(C context, Callback<C> callback, ByteBuffer... buf) throws IOException
    {
        getEndPoint().write(context,callback,buf);
    }
}
