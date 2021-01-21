//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;

public class BlockheadConnection extends AbstractConnection implements Connection.UpgradeTo
{
    private static final int BUFFER_SIZE = 4096;
    public static final String STATIC_REQUEST_HASH_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private final Logger log;
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private final Parser parser;
    private final Generator generator;
    private final ExtensionStack extensionStack;
    private final OutgoingNetwork networkOutgoing;
    private final IncomingCapture incomingCapture;
    private final CompletableFuture<BlockheadConnection> openFuture;
    private ByteBuffer networkBuffer;
    private HttpFields upgradeResponseHeaders;
    private HttpFields upgradeRequestHeaders;

    public BlockheadConnection(WebSocketPolicy policy, ByteBufferPool bufferPool, ExtensionStack extensionStack, CompletableFuture<BlockheadConnection> openFut, EndPoint endp, Executor executor)
    {
        super(endp, executor);
        this.log = Log.getLogger(this.getClass());
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.parser = new Parser(policy, bufferPool);
        this.generator = new Generator(policy, bufferPool, false);
        this.extensionStack = extensionStack;
        this.openFuture = openFut;

        this.extensionStack.configure(this.parser);
        this.extensionStack.configure(this.generator);

        // Wire up incoming frames (network -> extensionStack -> connection)
        this.parser.setIncomingFramesHandler(extensionStack);
        this.incomingCapture = new IncomingCapture();
        this.extensionStack.setNextIncoming(incomingCapture);

        // Wire up outgoing frames (connection -> extensionStack -> network)
        this.networkOutgoing = new OutgoingNetwork();
        extensionStack.setNextOutgoing(networkOutgoing);

        try
        {
            extensionStack.start();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to start ExtensionStack", e);
        }
    }

    public void abort()
    {
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        endPoint.shutdownOutput();
        endPoint.close();
    }

    @Override
    public void fillInterested()
    {
        // Handle situation where initial/prefill buffer (from upgrade) has created network buffer,
        // but there is no actual read interest (yet)
        if (BufferUtil.hasContent(networkBuffer))
        {
            fillAndParse();
        }
        else
        {
            super.fillInterested();
        }
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public LinkedBlockingQueue<WebSocketFrame> getFrameQueue()
    {
        return incomingCapture.incomingFrames;
    }

    public Generator getGenerator()
    {
        return generator;
    }

    public InetSocketAddress getLocalSocketAddress()
    {
        return getEndPoint().getLocalAddress();
    }

    public Parser getParser()
    {
        return parser;
    }

    public InetSocketAddress getRemoteSocketAddress()
    {
        return getEndPoint().getRemoteAddress();
    }

    public HttpFields getUpgradeRequestHeaders()
    {
        return upgradeRequestHeaders;
    }

    public HttpFields getUpgradeResponseHeaders()
    {
        return upgradeResponseHeaders;
    }

    public boolean isOpen()
    {
        return getEndPoint().isOpen();
    }

    @Override
    public void onFillable()
    {
        getNetworkBuffer();
        fillAndParse();
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        setInitialBuffer(buffer);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (this.openFuture != null)
            this.openFuture.complete(this);
        fillInterested();
    }

    public void processConnectionError(Throwable cause)
    {
        log.warn("Connection Error", cause);
        if (this.openFuture != null)
            this.openFuture.completeExceptionally(cause);
    }

    public void setUpgradeRequestHeaders(HttpFields upgradeRequestHeaders)
    {
        this.upgradeRequestHeaders = new HttpFields(upgradeRequestHeaders);
    }

    public void setUpgradeResponseHeaders(HttpFields upgradeResponseHeaders)
    {
        this.upgradeResponseHeaders = new HttpFields(upgradeResponseHeaders);
    }

    public void setIncomingFrameConsumer(Consumer<Frame> consumer)
    {
        this.incomingCapture.frameConsumer = consumer;
    }

    public void write(WebSocketFrame frame)
    {
        networkOutgoing.outgoingFrame(frame, null, BatchMode.OFF);
    }

    public void writeRaw(ByteBuffer buf) throws IOException
    {
        boolean done = false;
        while (!done)
        {
            done = getEndPoint().flush(buf);
        }
    }

    public void writeRaw(ByteBuffer buf, int numBytes) throws IOException
    {
        int len = Math.min(numBytes, buf.remaining());
        ByteBuffer slice = buf.slice();
        buf.limit(len);
        try
        {
            boolean done = false;
            while (!done)
            {
                done = getEndPoint().flush(slice);
            }
        }
        catch (IOException e)
        {
            throw e;
        }
        finally
        {
            buf.position(buf.position() + len);
        }
    }

    public void writeRawSlowly(ByteBuffer buf, int segmentSize) throws IOException
    {
        while (buf.remaining() > 0)
        {
            writeRaw(buf, segmentSize);
        }
    }

    /**
     * Extra bytes from the initial HTTP upgrade that need to
     * be processed by the websocket parser before starting
     * to read bytes from the connection
     *
     * @param initialBuffer the bytes of unconsumed content encountered during upgrade
     */
    protected void setInitialBuffer(ByteBuffer initialBuffer)
    {
        if (BufferUtil.hasContent(initialBuffer))
        {
            networkBuffer = bufferPool.acquire(initialBuffer.remaining(), true);
            BufferUtil.clearToFill(networkBuffer);
            BufferUtil.put(initialBuffer, networkBuffer);
            BufferUtil.flipToFlush(networkBuffer, 0);
        }
    }

    private void fillAndParse()
    {
        boolean interested = false;

        try
        {
            while (getEndPoint().isOpen())
            {
                ByteBuffer nBuffer = getNetworkBuffer();

                parser.parse(nBuffer);

                // Shouldn't reach this point if buffer has un-parsed bytes
                assert (!nBuffer.hasRemaining());

                int filled = getEndPoint().fill(nBuffer);

                if (log.isDebugEnabled())
                    log.debug("endpointFill() filled={}: {}", filled, BufferUtil.toDetailString(nBuffer));

                if (filled < 0)
                {
                    releaseNetworkBuffer(nBuffer);
                    return;
                }

                if (filled == 0)
                {
                    releaseNetworkBuffer(nBuffer);
                    interested = true;
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            processConnectionError(t);
        }
        finally
        {
            if (interested)
                fillInterested();
        }
    }

    private ByteBuffer getNetworkBuffer()
    {
        synchronized (this)
        {
            if (networkBuffer == null)
            {
                networkBuffer = bufferPool.acquire(BUFFER_SIZE, true);
            }
            return networkBuffer;
        }
    }

    private void releaseNetworkBuffer(ByteBuffer buffer)
    {
        synchronized (this)
        {
            assert (!buffer.hasRemaining());
            bufferPool.release(buffer);
            networkBuffer = null;
        }
    }

    public class IncomingCapture implements IncomingFrames
    {
        public final LinkedBlockingQueue<WebSocketFrame> incomingFrames = new LinkedBlockingQueue<>();
        public Consumer<Frame> frameConsumer;

        @Override
        public void incomingFrame(Frame frame)
        {
            if (frameConsumer != null)
                frameConsumer.accept(frame);

            incomingFrames.offer(WebSocketFrame.copy(frame));
        }
    }

    public class OutgoingNetwork implements OutgoingFrames
    {
        /**
         * Last step for networkOutgoing frames before the network buffer.
         * <p>
         * if ExtensionStack is in play, this should be wired up to the output from
         * the ExtensionStack.
         * </p>
         *
         * @param frame the frame to eventually write to the network layer.
         * @param callback the callback to notify when the frame is written.
         * @param batchMode ignored by BlockheadConnections
         */
        @Override
        public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
        {
            ByteBuffer header = generator.generateHeaderBytes(frame);
            ByteBuffer payload = frame.getPayload();
            if (payload == null)
                payload = BufferUtil.EMPTY_BUFFER;

            Callback jettyCallback = asJettyCallback(callback);
            try
            {
                getEndPoint().flush(header, payload);
                jettyCallback.succeeded();
            }
            catch (IOException e)
            {
                jettyCallback.failed(e);
            }
        }

        private Callback asJettyCallback(final WriteCallback writeCallback)
        {
            if (writeCallback instanceof org.eclipse.jetty.util.Callback)
            {
                return (org.eclipse.jetty.util.Callback)writeCallback;
            }
            else
            {
                return new WriteCallbackDelegate(writeCallback);
            }
        }
    }
}
