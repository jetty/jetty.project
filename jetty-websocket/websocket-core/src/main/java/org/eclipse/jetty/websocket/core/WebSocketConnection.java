//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Provides the implementation of {@link org.eclipse.jetty.io.Connection} that is suitable for WebSocket
 */
public class WebSocketConnection extends AbstractConnection implements Connection.UpgradeTo, Dumpable, OutgoingFrames, Runnable
{
    private final Logger LOG = Log.getLogger(this.getClass());

    /**
     * Minimum size of a buffer is the determined to be what would be the maximum framing header size (not including payload)
     */
    private static final int MIN_BUFFER_SIZE = Generator.MAX_HEADER_LENGTH;

    private final ByteBufferPool bufferPool;
    private final Generator generator;
    private final Parser parser;
    private final WebSocketChannel channel;

    // Connection level policy (before the session and local endpoint has been created)
    private final WebSocketPolicy policy;
    private final Flusher flusher;
    private final Random random;

    private long demand;
    private boolean fillingAndParsing;

    // Read / Parse variables
    private ReferencedBuffer networkBuffer;

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public WebSocketConnection(EndPoint endp,
                               Executor executor,
                               ByteBufferPool bufferPool,
                               WebSocketChannel channel)
    {
        this(endp, executor, bufferPool, channel, true);
    }

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public WebSocketConnection(EndPoint endp,
                               Executor executor,
                               ByteBufferPool bufferPool,
                               WebSocketChannel channel,
                               boolean validating)
    {
        super(endp, executor);

        Objects.requireNonNull(endp, "EndPoint");
        Objects.requireNonNull(channel, "Channel");
        Objects.requireNonNull(executor, "Executor");
        Objects.requireNonNull(bufferPool, "ByteBufferPool");

        this.bufferPool = bufferPool;

        this.policy = channel.getPolicy();
        this.channel = channel;

        this.generator = new Generator(bufferPool);
        this.parser = new Parser(bufferPool,policy.isAutoFragment())
        {
            @Override
            protected void checkFrameSize(byte opcode, int payloadLength) throws MessageTooLargeException, ProtocolException
            {
                super.checkFrameSize(opcode,payloadLength);
                if (payloadLength > policy.getMaxAllowedFrameSize())
                    throw new MessageTooLargeException("Cannot handle payload lengths larger than " + policy.getMaxAllowedFrameSize());
            }
            
        };
       
        this.flusher = new Flusher(policy.getOutputBufferSize(), generator, endp);
        this.setInputBufferSize(policy.getInputBufferSize());
        this.setMaxIdleTimeout(policy.getIdleTimeout());

        this.random = this.channel.getBehavior() == WebSocketCore.Behavior.CLIENT ? new Random(endp.hashCode()) : null;
    }

    @Override
    public Executor getExecutor()
    {
        return super.getExecutor();
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public Parser getParser()
    {
        return parser;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public InetSocketAddress getLocalAddress()
    {
        return getEndPoint().getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return getEndPoint().getRemoteAddress();
    }


    /**
     * Physical connection disconnect.
     * <p>
     * Not related to WebSocket close handshake.
     */
    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose() of physical connection");
        
        // TODO review all close paths
        IOException e = new IOException("Closed");
        flusher.terminate(e,true);
        channel.onClosed(e);
        super.onClose();
    }

    @Override
    public boolean onIdleExpired()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onIdleExpired()");

        channel.processError(new WebSocketTimeoutException("Connection Idle Timeout"));
        return true;
    }

    protected void onFrame(Parser.ParsedFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFrame({})", frame);
        
        final ReferencedBuffer referenced = frame.hasPayload() && !frame.isReleaseable()?networkBuffer:null;
        if (referenced!=null)
            referenced.retain();

        channel.onReceiveFrame(frame, new Callback()
        {
            @Override
            public void succeeded()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("succeeded onFrame({})", frame);

                frame.close();
                if (referenced!=null)
                    referenced.release();
                
                if (!channel.isDemanding())                    
                    demand(1);
            }

            @Override
            public void failed(Throwable cause)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failed onFrame(" + frame + ")", cause);
                
                frame.close();
                if (referenced!=null)
                    referenced.release();

                // notify session & endpoint
                channel.processError(cause);
            }
        });
    }

    private void acquireNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
                networkBuffer = new ReferencedBuffer(bufferPool,getInputBufferSize());             
        }
    }
    
    private void reacquireNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
                throw new IllegalStateException();
            
            if (networkBuffer.getBuffer().hasRemaining())
                throw new IllegalStateException();

            networkBuffer.release();
            networkBuffer = new ReferencedBuffer(bufferPool,getInputBufferSize());             
        }
    }
    
    private void releaseNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
                throw new IllegalStateException();
            
            if (networkBuffer.getBuffer().hasRemaining())
                throw new IllegalStateException();

            networkBuffer.release();
            networkBuffer = null;
        }
    }

    @Override
    public void onFillable()
    {
        if(LOG.isDebugEnabled())
            LOG.debug("onFillable()");
        fillAndParse();
    }
    
    @Override
    public void run()
    {
        if(LOG.isDebugEnabled())
            LOG.debug("run()");
        fillAndParse();
    }
       
    public void demand(long n)
    {
        if (n<=0)
            throw new IllegalArgumentException("Demand must be positive");
        
        boolean fillAndParse = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("demand {} d={} fp={} {} {}",n,demand,fillingAndParsing,networkBuffer,this);
            
            if (demand<0)
                return;
            
            try
            {
                demand = Math.addExact(demand,n);
            }
            catch(ArithmeticException e)
            {
                demand = Long.MAX_VALUE;
            }
            
            if (!fillingAndParsing)
            {
                fillingAndParsing = true;
                fillAndParse = true;
            }            
        }
        
        if (fillAndParse)
        {
            // TODO can we just fillAndParse();
            getExecutor().execute(this);
        }
    }
    
    public boolean moreDemand()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("moreDemand? d={} fp={} {} {}",demand,fillingAndParsing,networkBuffer,this);
            
            if (!fillingAndParsing)
                throw new IllegalStateException();
            if (demand>0)
                return true;
            
            if (demand==0)
                fillingAndParsing = false;

            if (networkBuffer.isEmpty())
                releaseNetworkBuffer();
            
            return false;            
        }
    }

    public boolean meetDemand()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("meetDemand d={} fp={} {} {}",demand,fillingAndParsing,networkBuffer,this);
            
            if (demand==0)
                throw new IllegalStateException();
            if (!fillingAndParsing)
                throw new IllegalStateException();
                
            if (demand<0)
                return false;
                
            demand--;
            return true;
        }
    }

    public void cancelDemand()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cancelDemand d={} fp={} {} {}",demand,fillingAndParsing,networkBuffer,this);
            
            demand = -1;
        }
    }
    
    
    private void fillAndParse()
    {
        acquireNetworkBuffer();   
                        
        try
        {
            while (true)
            {
                // Parse and handle frames
                while(!networkBuffer.isEmpty())
                {
                    Parser.ParsedFrame frame = parser.parse(networkBuffer.getBuffer());
                    if (frame==null)
                        break;
                             
                   if(meetDemand())
                       onFrame(frame);

                    if (!moreDemand())
                    {
                        return;
                    }
                }

                // buffer must be empty here because parser is fully consuming
                assert(networkBuffer.isEmpty());
                
                if (!getEndPoint().isOpen())
                {
                    releaseNetworkBuffer();
                    return;
                }
                
                // If more references that 1(us), don't refill into buffer and risk compaction.
                if (networkBuffer.getReferences()>1)
                    reacquireNetworkBuffer();
                
                int filled = getEndPoint().fill(networkBuffer.getBuffer()); // TODO check if compact is possible.

                if (LOG.isDebugEnabled())
                    LOG.debug("endpointFill() filled={}: {}", filled, networkBuffer);

                if (filled < 0)
                {
                    releaseNetworkBuffer();
                    channel.onClosed(new IOException("Read EOF"));
                    return;
                }

                if (filled == 0)
                {
                    releaseNetworkBuffer();
                    fillInterested();
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t.toString());
            BufferUtil.clear(networkBuffer.getBuffer());
            releaseNetworkBuffer();
            channel.processError(t);
        }
    }


    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     *
     * @param prefilled the bytes of prefilled content encountered during upgrade
     */
    protected void setInitialBuffer(ByteBuffer prefilled)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("set Initial Buffer - {}", BufferUtil.toDetailString(prefilled));
        }

        if ((prefilled != null) && (prefilled.hasRemaining()))
        {
            synchronized (this)
            {
                networkBuffer = new ReferencedBuffer(bufferPool,prefilled.remaining());
            }
            ByteBuffer buffer = networkBuffer.getBuffer();
            BufferUtil.clearToFill(buffer);
            BufferUtil.put(prefilled, buffer);
            BufferUtil.flipToFlush(buffer, 0);
        }
    }

    /**
     * Physical connection Open.
     */
    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}",this);

        // Connection Settings
        long channelIdleTimeout = channel.getIdleTimeout(TimeUnit.MILLISECONDS);
        if (channelIdleTimeout >= -1)
        {
            this.setMaxIdleTimeout(channelIdleTimeout);
        }

        // Open Channel
        channel.onOpen();
        super.onOpen();
    }

    /**
     * Event for no activity on connection (read or write)
     * @return true to signal that the endpoint must be closed, false to keep the endpoint open
     */
    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        channel.processError(new WebSocketTimeoutException("Timeout on Read",timeout));
        return false;
    }


    @Override
    public void setInputBufferSize(int inputBufferSize)
    {
        if (inputBufferSize < MIN_BUFFER_SIZE)
        {
            throw new IllegalArgumentException("Cannot have buffer size less than " + MIN_BUFFER_SIZE);
        }
        super.setInputBufferSize(inputBufferSize);
    }

    public void setMaxIdleTimeout(long ms)
    {
        if (ms >= 0)
        {
            getEndPoint().setIdleTimeout(ms);
        }
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString()).append(System.lineSeparator());
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[%s,p=%s,f=%s,g=%s]",
                getClass().getSimpleName(),
                hashCode(),
                channel.getBehavior(),
                parser,
                flusher,
                generator);
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;

        EndPoint endp = getEndPoint();
        if (endp != null)
        {
            result = prime * result + endp.getLocalAddress().hashCode();
            result = prime * result + endp.getRemoteAddress().hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        WebSocketConnection other = (WebSocketConnection) obj;
        EndPoint endp = getEndPoint();
        EndPoint otherEndp = other.getEndPoint();
        if (endp == null)
        {
            if (otherEndp != null)
                return false;
        }
        else if (!endp.equals(otherEndp))
            return false;
        return true;
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     */
    @Override
    public void onUpgradeTo(ByteBuffer prefilled)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onUpgradeTo({})", BufferUtil.toDetailString(prefilled));
        }

        setInitialBuffer(prefilled);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        if (channel.getBehavior()== WebSocketCore.Behavior.CLIENT)
        {
            Frame wsf = frame;
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            wsf.setMask(mask);
        }
        flusher.enqueue(frame,callback,batchMode);
    }

    private class Flusher extends FrameFlusher
    {
        private Flusher(int bufferSize, Generator generator, EndPoint endpoint)
        {
            super(bufferPool, generator, endpoint, bufferSize, 8);
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            super.onCompleteFailure(x);
            channel.processError(x);
        }
    }
}
