//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ByteArrayEndPoint.
 */
public class ByteArrayEndPoint extends AbstractEndPoint
{
    private static SocketAddress noSocketAddress()
    {
        try
        {
            return new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0);
        }
        catch (Throwable x)
        {
            throw new RuntimeIOException(x);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ByteArrayEndPoint.class);
    private static final SocketAddress NO_SOCKET_ADDRESS = noSocketAddress();
    private static final ByteBuffer EOF = BufferUtil.allocate(0);

    private final Runnable _runFillable = () -> getFillInterest().fillable();
    private final AutoLock _lock = new AutoLock();
    private final Condition _hasOutput = _lock.newCondition();
    private final Queue<ByteBuffer> _inQ = new ArrayDeque<>();
    private final RetainableByteBuffer.DynamicCapacity _buffer;

    public ByteArrayEndPoint()
    {
        this(null, 0, null, -1, false);
    }

    /**
     * @param input the input bytes
     * @param outputSize the output size or -1 for default
     */
    public ByteArrayEndPoint(byte[] input, int outputSize)
    {
        this(null, 0, input != null ? BufferUtil.toBuffer(input) : null, outputSize, false);
    }

    /**
     * @param input the input string (converted to bytes using default encoding charset)
     * @param outputSize the output size or -1 for default
     */
    public ByteArrayEndPoint(String input, int outputSize)
    {
        this(null, 0, input != null ? BufferUtil.toBuffer(input) : null, outputSize, false);
    }

    /**
     * @param input the input bytes
     * @param outputSize the output size or -1 for default
     * @param growable {@code true} if the output buffer may grow
     */
    public ByteArrayEndPoint(byte[] input, int outputSize, boolean growable)
    {
        this(null, 0, input != null ? BufferUtil.toBuffer(input) : null, outputSize, growable);
    }

    /**
     * @param input the input string (converted to bytes using default encoding charset)
     * @param outputSize the output size or -1 for default
     * @param growable {@code true} if the output buffer may grow
     */
    public ByteArrayEndPoint(String input, int outputSize, boolean growable)
    {
        this(null, 0, input != null ? BufferUtil.toBuffer(input) : null, outputSize, growable);
    }

    public ByteArrayEndPoint(Scheduler scheduler, long idleTimeoutMs)
    {
        this(scheduler, idleTimeoutMs, null, -1, false);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, byte[] input, int outputSize)
    {
        this(timer, idleTimeoutMs, input != null ? BufferUtil.toBuffer(input) : null, outputSize, false);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, String input, int outputSize)
    {
        this(timer, idleTimeoutMs, input != null ? BufferUtil.toBuffer(input) : null, outputSize, false);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, ByteBuffer input, int outputSize, boolean growable)
    {
        super(timer);
        if (BufferUtil.hasContent(input))
            addInput(input);

        _buffer = growable
            ? new RetainableByteBuffer.DynamicCapacity(null, false, -1, outputSize)
            : new RetainableByteBuffer.DynamicCapacity(null, false, outputSize);
        setIdleTimeout(idleTimeoutMs);
        onOpen();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return NO_SOCKET_ADDRESS;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return NO_SOCKET_ADDRESS;
    }

    @Override
    public void doShutdownOutput()
    {
        super.doShutdownOutput();
        try (AutoLock l = _lock.lock())
        {
            _hasOutput.signalAll();
        }
    }

    @Override
    public void doClose()
    {
        super.doClose();
        try (AutoLock l = _lock.lock())
        {
            _hasOutput.signalAll();
        }
    }

    @Override
    protected void onIncompleteFlush()
    {
        // Don't need to do anything here as takeOutput does the signalling.
    }

    protected void execute(Runnable task)
    {
        new Thread(task, "BAEPoint-" + Integer.toHexString(hashCode())).start();
    }

    @Override
    protected void needsFillInterest() throws IOException
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (!isOpen())
                throw new ClosedChannelException();

            ByteBuffer in = _inQ.peek();
            if (LOG.isDebugEnabled())
                LOG.debug("{} needsFillInterest EOF={} {}", this, in == EOF, BufferUtil.toDetailString(in));
            if (BufferUtil.hasContent(in) || isEOF(in))
                execute(_runFillable);
        }
    }

    /**
     *
     */
    public void addInputEOF()
    {
        addInput((ByteBuffer)null);
    }

    /**
     * @param in The in to set.
     */
    public void addInput(ByteBuffer in)
    {
        boolean fillable = false;
        try (AutoLock ignored = _lock.lock())
        {
            if (isEOF(_inQ.peek()))
                throw new RuntimeIOException(new EOFException());
            boolean wasEmpty = _inQ.isEmpty();
            if (in == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addEOFAndRun=true", this);
                _inQ.add(EOF);
                fillable = true;
            }
            if (BufferUtil.hasContent(in))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addInputAndRun={} {}", this, wasEmpty, BufferUtil.toDetailString(in));
                _inQ.add(in);
                fillable = wasEmpty;
            }
        }
        if (fillable)
            _runFillable.run();
    }

    public void addInput(String s)
    {
        addInput(BufferUtil.toBuffer(s, StandardCharsets.UTF_8));
    }

    public void addInput(String s, Charset charset)
    {
        addInput(BufferUtil.toBuffer(s, charset));
    }

    public void addInputAndExecute(String s)
    {
        addInputAndExecute(BufferUtil.toBuffer(s, StandardCharsets.UTF_8));
    }

    public void addInputAndExecute(ByteBuffer in)
    {
        boolean fillable = false;
        try (AutoLock ignored = _lock.lock())
        {
            if (isEOF(_inQ.peek()))
                throw new RuntimeIOException(new EOFException());
            boolean wasEmpty = _inQ.isEmpty();
            if (in == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addEOFAndExecute=true", this);
                _inQ.add(EOF);
                fillable = true;
            }
            if (BufferUtil.hasContent(in))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addInputAndExecute={} {}", this, wasEmpty, BufferUtil.toDetailString(in));
                _inQ.add(in);
                fillable = wasEmpty;
            }
        }
        if (fillable)
            execute(_runFillable);
    }

    /**
     * @return Returns the out.
     */
    public ByteBuffer getOutput()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _buffer.getByteBuffer();
        }
    }

    /**
     * @return Returns the out.
     */
    public String getOutputString()
    {
        return getOutputString(StandardCharsets.UTF_8);
    }

    /**
     * @param charset the charset to encode the output as
     * @return Returns the out.
     */
    public String getOutputString(Charset charset)
    {
        return BufferUtil.toString(getOutput(), charset);
    }

    /**
     * @return Returns the out.
     */
    public ByteBuffer takeOutput()
    {
        ByteBuffer taken;

        try (AutoLock ignored = _lock.lock())
        {
            taken = _buffer.takeRetainableByteBuffer().getByteBuffer();
        }
        getWriteFlusher().completeWrite();
        return taken;
    }

    /**
     * Wait for some output
     *
     * @param time Time to wait
     * @param unit Units for time to wait
     * @return The buffer of output
     * @throws InterruptedException if interrupted
     */
    public ByteBuffer waitForOutput(long time, TimeUnit unit) throws InterruptedException
    {
        ByteBuffer taken;

        try (AutoLock ignored = _lock.lock())
        {
            while (_buffer.isEmpty() && !isOutputShutdown())
            {
                if (!_hasOutput.await(time, unit))
                    return null;
            }
            taken = _buffer.takeRetainableByteBuffer().getByteBuffer();
        }
        getWriteFlusher().completeWrite();
        return taken;
    }

    /**
     * @return Returns the out.
     */
    public String takeOutputString()
    {
        return takeOutputString(StandardCharsets.UTF_8);
    }

    /**
     * @param charset the charset to encode the output as
     * @return Returns the out.
     */
    public String takeOutputString(Charset charset)
    {
        ByteBuffer buffer = takeOutput();
        return BufferUtil.toString(buffer, charset);
    }

    /**
     * @param out The out to set.
     */
    @Deprecated
    public void setOutput(ByteBuffer out)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * @return {@code true} if there are bytes remaining to be read from the encoded input
     */
    public boolean hasMore()
    {
        return getOutput().position() > 0;
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int filled = 0;
        try (AutoLock ignored = _lock.lock())
        {
            while (true)
            {
                if (!isOpen())
                    throw new EofException("CLOSED");

                if (isInputShutdown())
                    return -1;

                if (_inQ.isEmpty())
                    break;

                ByteBuffer in = _inQ.peek();
                if (isEOF(in))
                {
                    filled = -1;
                    break;
                }

                if (BufferUtil.hasContent(in))
                {
                    filled = BufferUtil.append(buffer, in);
                    if (BufferUtil.isEmpty(in))
                        _inQ.poll();
                    break;
                }
                _inQ.poll();
            }
        }

        if (filled > 0)
            notIdle();
        else if (filled < 0)
            shutdownInput();
        return filled;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        boolean flushed = true;
        try (AutoLock ignored = _lock.lock())
        {
            if (!isOpen())
                throw new IOException("CLOSED");
            if (isOutputShutdown())
                throw new IOException("OSHUT");

            boolean notIdle = false;

            for (ByteBuffer b : buffers)
            {
                int remaining = b.remaining();
                flushed = _buffer.append(b);
                notIdle |= b.remaining() < remaining;
                if (!flushed)
                    break;
            }

            if (notIdle)
            {
                notIdle();
                _hasOutput.signalAll();
            }

            return flushed;
        }
    }

    @Override
    public void reset()
    {
        try (AutoLock ignored = _lock.lock())
        {
            _inQ.clear();
            _hasOutput.signalAll();
            _buffer.clear();
        }
        super.reset();
    }

    @Override
    public Object getTransport()
    {
        return null;
    }

    /**
     * @return the growOutput
     */
    public boolean isGrowOutput()
    {
        return _buffer instanceof RetainableByteBuffer.DynamicCapacity;
    }

    /**
     * Set the growOutput to set.
     * @param growOutput the growOutput to set
     */
    @Deprecated
    public void setGrowOutput(boolean growOutput)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString()
    {
        int q;
        Object b;
        String o;
        try (AutoLock lock = _lock.tryLock())
        {
            boolean held = lock.isHeldByCurrentThread();
            q = held ? _inQ.size() : -1;
            b = held ? _inQ.peek() : "?";
            o = held ? _buffer.toString() : "?";
        }
        return String.format("%s[q=%d,q[0]=%s,o=%s]", super.toString(), q, b, o);
    }

    /**
     * Compares a ByteBuffer Object to EOF by Reference
     *
     * @param buffer the input ByteBuffer to be compared to EOF
     * @return Whether the reference buffer is equal to that of EOF
     */
    private static boolean isEOF(ByteBuffer buffer)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isEof = (buffer == EOF);
        return isEof;
    }
}
