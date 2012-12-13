//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ForkInvoker;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteResult;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketSession;

/**
 * Provides the implementation of {@link WebSocketConnection} within the framework of the new {@link Connection} framework of jetty-io
 */
public abstract class AbstractWebSocketConnection extends AbstractConnection implements LogicalConnection
{
    private abstract class AbstractFrameBytes extends FutureCallback implements FrameBytes
    {
        protected final Logger LOG;
        protected final Frame frame;

        public AbstractFrameBytes(Frame frame)
        {
            this.frame = frame;
            this.LOG = Log.getLogger(this.getClass());
        }

        @Override
        public void complete()
        {
            if (!isDone())
            {
                AbstractWebSocketConnection.this.complete(this);
            }
        }

        @Override
        public void fail(Throwable t)
        {
            failed(t);
            flush();
        }

        /**
         * Entry point for EndPoint.write failure
         */
        @Override
        public void failed(Throwable x)
        {
            // Log failure
            if (x instanceof EofException)
            {
                // Abbreviate the EofException
                LOG.warn("failed() - " + EofException.class);
            }
            else
            {
                LOG.warn("failed()",x);
            }
            flushing = false;
            queue.fail(x);
            super.failed(x);
        }

        /**
         * Entry point for EndPoint.write success
         */
        @Override
        public void succeeded()
        {
            super.succeeded();
            synchronized (queue)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Completed Write of {} ({} frame(s) in queue)",this,queue.size());
                }
                flushing = false;
            }
            complete();
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class ControlFrameBytes extends AbstractFrameBytes
    {
        private ByteBuffer buffer;
        private ByteBuffer origPayload;

        public ControlFrameBytes(Frame frame)
        {
            super(frame);
        }

        @Override
        public void complete()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("complete() - frame: {}",frame);
            }

            if (buffer != null)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Releasing Buffer: {}",BufferUtil.toDetailString(buffer));
                }

                getBufferPool().release(buffer);
                buffer = null;
            }

            super.complete();

            if (frame.getType().getOpCode() == OpCode.CLOSE)
            {
                CloseInfo close = new CloseInfo(origPayload,false);
                // TODO: change into DisconnectWebSocketCallback
                onWriteWebSocketClose(close);
            }

            getBufferPool().release(origPayload);
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            if (buffer == null)
            {
                if (frame.hasPayload())
                {
                    int len = frame.getPayload().remaining();
                    origPayload = getBufferPool().acquire(len,false);
                    BufferUtil.put(frame.getPayload(),origPayload);
                }
                buffer = getGenerator().generate(frame);

            }
            return buffer;
        }
    }

    private class DataFrameBytes extends AbstractFrameBytes
    {
        private ByteBuffer buffer;

        public DataFrameBytes(Frame frame)
        {
            super(frame);
        }

        @Override
        public void complete()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("complete() - frame.remaining() = {}",frame.remaining());
            }

            if (buffer != null)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Releasing Buffer: {}",BufferUtil.toDetailString(buffer));
                }

                getBufferPool().release(buffer);
                buffer = null;
            }

            if (frame.remaining() > 0)
            {
                LOG.debug("More to send");
                // We have written a partial frame per windowing size.
                // We need to keep the correct ordering of frames, to avoid that another
                // Data frame for the same stream is written before this one is finished.
                queue.prepend(this);
            }
            else
            {
                LOG.debug("Send complete");
                super.complete();
            }
            flush();
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            try
            {
                int windowSize = getInputBufferSize();
                buffer = getGenerator().generate(windowSize,frame);
                return buffer;
            }
            catch (Throwable x)
            {
                fail(x);
                return null;
            }
        }
    }

    private class FlushInvoker extends ForkInvoker<Callback>
    {
        private FlushInvoker()
        {
            super(4);
        }

        @Override
        public void call(Callback callback)
        {
            callback.succeeded();
            flush();
        }

        @Override
        public void fork(final Callback callback)
        {
            execute(new Runnable()
            {
                @Override
                public void run()
                {
                    callback.succeeded();
                    flush();
                }
            });
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x",FlushInvoker.class.getSimpleName(),hashCode());
        }
    }

    public interface FrameBytes extends Callback, Future<Void>
    {
        public abstract void complete();

        public abstract void fail(Throwable t);

        public abstract ByteBuffer getByteBuffer();
    }

    private static final Logger LOG = Log.getLogger(AbstractWebSocketConnection.class);
    private static final Logger LOG_FRAMES = Log.getLogger("org.eclipse.jetty.websocket.io.Frames");

    private final ForkInvoker<Callback> invoker = new FlushInvoker();
    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketPolicy policy;
    private final FrameQueue queue = new FrameQueue();
    private final AtomicBoolean suspendToken;
    private WebSocketSession session;
    private List<ExtensionConfig> extensions;
    private boolean flushing;
    private boolean isFilling;
    private IOState ioState;

    public AbstractWebSocketConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp,executor,EXECUTE_ONFILLABLE); // TODO review if this is best.  Specially with MUX
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy,bufferPool);
        this.scheduler = scheduler;
        this.extensions = new ArrayList<>();
        this.suspendToken = new AtomicBoolean(false);
        this.ioState = new IOState();
        this.ioState.setState(ConnectionState.CONNECTING);
    }

    @Override
    public void close()
    {
        close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        enqueClose(statusCode,reason);
    }

    public void complete(final Callback callback)
    {
        if (ioState.isOpen())
        {
            invoker.invoke(callback);
        }
    }

    @Override
    public void disconnect()
    {
        disconnect(false);
    }

    public void disconnect(boolean onlyOutput)
    {
        ioState.setState(ConnectionState.CLOSED);
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        LOG.debug("Shutting down output {}",endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            LOG.debug("Closing {}",endPoint);
            endPoint.close();
        }
    }

    /**
     * Enqueue a close frame.
     * 
     * @param statusCode
     *            the WebSocket status code.
     * @param reason
     *            the (optional) reason string. (null is allowed)
     * @see StatusCode
     */
    private void enqueClose(int statusCode, String reason)
    {
        CloseInfo close = new CloseInfo(statusCode,reason);
        try
        {
            outgoingFrame(close.asFrame());
        }
        catch (IOException e)
        {
            LOG.info("Unable to enque close frame",e);
            // TODO: now what?
            disconnect();
        }
    }

    private void execute(Runnable task)
    {
        try
        {
            getExecutor().execute(task);
        }
        catch (RejectedExecutionException e)
        {
            LOG.debug("Job not dispatched: {}",task);
        }
    }

    public void flush()
    {
        FrameBytes frameBytes = null;
        ByteBuffer buffer = null;

        synchronized (queue)
        {
            if (queue.isFailed())
            {
                LOG.debug(".flush() - queue is in failed state");
                return;
            }

            if (flushing || queue.isEmpty())
            {
                return;
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug(".flush() - flushing={} - queue.size = {}",flushing,queue.size());
            }

            frameBytes = queue.pop();

            if (!isOpen())
            {
                // No longer have an open connection, drop them all.
                queue.fail(new WebSocketException("Connection closed"));
                return;
            }

            LOG.debug("Next FrameBytes: {}",frameBytes);

            buffer = frameBytes.getByteBuffer();

            if (buffer == null)
            {
                return;
            }

            flushing = true;

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Flushing {}, {} frame(s) in queue",frameBytes,queue.size());
            }
        }

        write(buffer,frameBytes);
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

    public Generator getGenerator()
    {
        return generator;
    }

    @Override
    public IOState getIOState()
    {
        return ioState;
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

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    @Override
    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public boolean isOpen()
    {
        return getIOState().isOpen() && getEndPoint().isOpen();
    }

    @Override
    public boolean isReading()
    {
        return isFilling;
    }

    @Override
    public void onClose()
    {
        super.onClose();
        this.getIOState().setState(ConnectionState.CLOSED);
    }

    @Override
    public void onFillable()
    {
        // LOG.debug("{} onFillable()",policy.getBehavior());
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(),false);
        BufferUtil.clear(buffer);
        boolean readMore = false;
        try
        {
            isFilling = true;
            readMore = (read(buffer) != -1);
        }
        finally
        {
            bufferPool.release(buffer);
        }

        if (readMore && (suspendToken.get() == false))
        {
            fillInterested();
        }
        else
        {
            isFilling = false;
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        this.ioState.setState(ConnectionState.OPEN);
        LOG.debug("fillInterested");
        fillInterested();
    }

    @Override
    protected boolean onReadTimeout()
    {
        LOG.debug("Read Timeout. disconnecting connection");
        // TODO: notify end user websocket of read timeout?
        return true;
    }

    public void onWriteWebSocketClose(CloseInfo close)
    {
        if (ioState.onCloseHandshake(false,close))
        {
            disconnect();
        }
    }

    @Override
    public Future<WriteResult> outgoingFrame(Frame frame) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({})",frame);
        }

        Future<WriteResult> future = null;

        synchronized (this)
        {
            FrameBytes bytes = null;

            if (frame.getType().isControl())
            {
                bytes = new ControlFrameBytes(frame);
            }
            else
            {
                bytes = new DataFrameBytes(frame);
            }

            future = new WriteResultFuture(bytes);

            if (isOpen())
            {
                if (frame.getType().getOpCode() == OpCode.PING)
                {
                    queue.prepend(bytes);
                }
                else
                {
                    queue.append(bytes);
                }
            }
        }

        flush();

        return future;
    }

    private int read(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return 0;
                }
                else if (filled < 0)
                {
                    LOG.debug("read - EOF Reached");
                    return -1;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                    }
                    parser.parse(buffer);
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn(e);
            enqueClose(StatusCode.PROTOCOL,e.getMessage());
            return -1;
        }
        catch (CloseException e)
        {
            LOG.warn(e);
            enqueClose(e.getStatusCode(),e.getMessage());
            return -1;
        }
    }

    @Override
    public void resume()
    {
        if (suspendToken.getAndSet(false))
        {
            fillInterested();
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

    @Override
    public void setSession(WebSocketSession session)
    {
        this.session = session;
    }

    @Override
    public SuspendToken suspend()
    {
        suspendToken.set(true);
        return this;
    }

    @Override
    public String toString()
    {
        return String.format("%s{g=%s,p=%s}",super.toString(),generator,parser);
    }

    private <C> void write(ByteBuffer buffer, FrameBytes frameBytes)
    {
        EndPoint endpoint = getEndPoint();

        if (LOG_FRAMES.isDebugEnabled())
        {
            LOG_FRAMES.debug("{} Writing {} of {} ",policy.getBehavior(),BufferUtil.toDetailString(buffer),frameBytes);
        }

        if (!isOpen())
        {
            // connection is closed, STOP WRITING, geez.
            frameBytes.failed(new WebSocketException("Connection closed"));
            return;
        }

        try
        {
            endpoint.write(frameBytes,buffer);
        }
        catch (Throwable t)
        {
            frameBytes.failed(t);
        }
    }
}
