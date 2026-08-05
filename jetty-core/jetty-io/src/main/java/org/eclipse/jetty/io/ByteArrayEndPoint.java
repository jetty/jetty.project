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
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.Aggregator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

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
            throw ExceptionUtil.asRuntimeException(x);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ByteArrayEndPoint.class);
    private static final SocketAddress NO_SOCKET_ADDRESS = noSocketAddress();
    private static final RetainableByteBuffer EOF = RetainableByteBuffer.wrap(BufferUtil.allocate(0));

    private final Runnable _runFillable = () -> getFillInterest().fillable();
    private final AutoLock _lock = new AutoLock();
    private final Condition _hasOutput = _lock.newCondition();
    private final Queue<RetainableByteBuffer> _inputQueue = new ArrayDeque<>();
    private final Aggregator _outputAggregator;

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
        this(null, 0, input != null ? ByteBuffer.wrap(input) : null, outputSize, false);
    }

    /**
     * @param input the input string (converted to bytes using default encoding charset)
     * @param outputSize the output size or -1 for default
     */
    public ByteArrayEndPoint(String input, int outputSize)
    {
        this(null, 0, input != null ? UTF_8.encode(input) : null, outputSize, false);
    }

    /**
     * @param input the input bytes
     * @param outputSize the output size or -1 for default
     * @param growable {@code true} if the output buffer may grow
     */
    public ByteArrayEndPoint(byte[] input, int outputSize, boolean growable)
    {
        this(null, 0, input != null ? ByteBuffer.wrap(input) : null, outputSize, growable);
    }

    /**
     * @param input the input string (converted to bytes using default encoding charset)
     * @param outputSize the output size or -1 for default
     * @param growable {@code true} if the output buffer may grow
     */
    public ByteArrayEndPoint(String input, int outputSize, boolean growable)
    {
        this(null, 0, input != null ? UTF_8.encode(input) : null, outputSize, growable);
    }

    public ByteArrayEndPoint(Scheduler scheduler, long idleTimeoutMs)
    {
        this(scheduler, idleTimeoutMs, null, -1, false);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, byte[] input, int outputSize)
    {
        this(timer, idleTimeoutMs, input != null ? ByteBuffer.wrap(input) : null, outputSize, false);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, String input, int outputSize)
    {
        this(timer, idleTimeoutMs, input != null ? UTF_8.encode(input) : null, outputSize, false);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, ByteBuffer input, int outputSize, boolean growable)
    {
        super(timer);
        if (BufferUtil.hasContent(input))
            addInput(input);
        _outputAggregator = new Aggregator(growable, outputSize > 0 ? outputSize : 1024);
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
        try (AutoLock _ = _lock.lock())
        {
            _hasOutput.signalAll();
        }
    }

    @Override
    public void doClose()
    {
        super.doClose();
        _outputAggregator.close();
        try (AutoLock _ = _lock.lock())
        {
            _hasOutput.signalAll();
        }
    }

    @Override
    protected void onIncompleteFlush()
    {
        // Don't need to do anything here as takeOutput does the signaling.
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

            RetainableByteBuffer in = _inputQueue.peek();
            if (LOG.isDebugEnabled())
                LOG.debug("{} needsFillInterest EOF={} {}", this, in == EOF, in);
            if ((in != null && in.hasRemaining()) || isEOF(in))
                execute(_runFillable);
        }
    }

    /**
     *
     */
    public void addInputEOF()
    {
        addInput((RetainableByteBuffer)null);
    }

    /**
     * @param in The in to set.
     */
    public void addInput(ByteBuffer in)
    {
        addInput(in == null ? null : RetainableByteBuffer.wrap(in));
    }

    public void addInput(RetainableByteBuffer in)
    {
        addInput(in, false);
    }

    public void addInput(String s)
    {
        addInput(BufferUtil.toReadableBuffer(s, UTF_8));
    }

    public void addInput(String s, Charset charset)
    {
        addInput(BufferUtil.toReadableBuffer(s, charset));
    }

    public void addInputAndExecute(String s)
    {
        addInputAndExecute(BufferUtil.toReadableBuffer(s, UTF_8));
    }

    public void addInputAndExecute(RetainableByteBuffer in)
    {
        addInput(in, true);
    }

    private void addInput(RetainableByteBuffer in, boolean execute)
    {
        boolean fillable = false;
        try (AutoLock ignored = _lock.lock())
        {
            if (isEOF(_inputQueue.peek()))
                throw new UncheckedIOException(new EOFException());
            boolean wasEmpty = _inputQueue.isEmpty();
            if (in == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addEOF execute={}", this, execute);
                _inputQueue.add(EOF);
                fillable = true;
            }
            if (in != null && in.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addInput execute={} notify={}, {}", this, execute, wasEmpty, in);
                in.retain();
                _inputQueue.add(in);
                fillable = wasEmpty;
            }
        }
        if (fillable)
        {
            if (execute)
                execute(_runFillable);
            else
                _runFillable.run();
        }
    }

    public long outputRemaining()
    {
        return _outputAggregator.remaining();
    }

    /// Copies the output bytes into a [String] decoding them using the given [Charset].
    ///
    /// The output in this `ByteArrayEndPoint` remains unchanged.
    ///
    /// @param charset the charset to use to decode the output bytes
    /// @return the output as a [String]
    public String getOutputString(Charset charset)
    {
        return _outputAggregator.getString(charset);
    }

    /**
     * @return Returns the out.
     */
    public RetainableByteBuffer takeOutput()
    {
        RetainableByteBuffer result;
        try (AutoLock ignored = _lock.lock())
        {
            result = _outputAggregator.take();
        }
        getWriteFlusher().completeWrite();
        return result;
    }

    public RetainableByteBuffer awaitForOutput(long time, TimeUnit unit) throws InterruptedException
    {
        RetainableByteBuffer result;
        try (AutoLock ignored = _lock.lock())
        {
            while (outputRemaining() == 0 && !isOutputShutdown())
            {
                if (!_hasOutput.await(time, unit))
                    return null;
            }
            result = _outputAggregator.take();
        }
        getWriteFlusher().completeWrite();
        return result;
    }

    /**
     * @return Returns the out.
     */
    public String takeOutputString()
    {
        return takeOutputString(UTF_8);
    }

    /**
     * @param charset the charset to encode the output as
     * @return Returns the out.
     */
    public String takeOutputString(Charset charset)
    {
        RetainableByteBuffer buffer = takeOutput();
        String result = buffer.getString(charset);
        buffer.release();
        return result;
    }

    @Override
    public int fill(RetainableByteBuffer.Mutable buffer) throws IOException
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

                if (_inputQueue.isEmpty())
                    break;

                RetainableByteBuffer in = _inputQueue.peek();
                if (isEOF(in))
                {
                    filled = -1;
                    break;
                }

                if (in != null && in.hasRemaining())
                {
                    filled = (int)buffer.append(in);
                    if (!in.hasRemaining())
                    {
                        _inputQueue.poll();
                        in.release();
                    }
                    break;
                }

                _inputQueue.poll();
                Retainable.dispose(in);
            }
        }

        if (filled > 0)
            notIdle();
        else if (filled < 0)
            shutdownInput();
        return filled;
    }

    @Override
    public boolean flush(RetainableByteBuffer buffer) throws IOException
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (!isOpen())
                throw new IOException("CLOSED");
            if (isOutputShutdown())
                throw new IOException("OSHUT");

            long remaining = buffer.remaining();
            long appended = _outputAggregator.append(buffer);
            boolean flushed = remaining - appended == 0;
            boolean notIdle = appended > 0;

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
            _inputQueue.clear();
            _hasOutput.signalAll();
            _outputAggregator.clear();
        }
        super.reset();
    }

    @Override
    public Object getTransport()
    {
        return null;
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
            q = held ? _inputQueue.size() : -1;
            b = held ? _inputQueue.peek() : "?";
            o = held ? _outputAggregator.toString() : "?";
        }
        return String.format("%s[q=%d,q[0]=%s,o=%s]", super.toString(), q, b, o);
    }

    /**
     * Compares a ByteBuffer Object to EOF by Reference
     *
     * @param buffer the input ByteBuffer to be compared to EOF
     * @return Whether the reference buffer is equal to that of EOF
     */
    private static boolean isEOF(RetainableByteBuffer buffer)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isEof = (buffer == EOF);
        return isEof;
    }
}
