//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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
    static final Logger LOG = LoggerFactory.getLogger(ByteArrayEndPoint.class);
    static final InetAddress NOIP;
    static final InetSocketAddress NOIPPORT;

    static
    {
        InetAddress noip = null;
        try
        {
            noip = Inet4Address.getByName("0.0.0.0");
        }
        catch (UnknownHostException e)
        {
            LOG.warn("Unable to get IPv4 no-ip reference for 0.0.0.0", e);
        }
        finally
        {
            NOIP = noip;
            NOIPPORT = new InetSocketAddress(NOIP, 0);
        }
    }

    private static final ByteBuffer EOF = BufferUtil.allocate(0);

    private final Runnable _runFillable = () -> getFillInterest().fillable();
    private final AutoLock _lock = new AutoLock();
    private final Condition _hasOutput = _lock.newCondition();
    private final Queue<ByteBuffer> _inQ = new ArrayDeque<>();
    private ByteBuffer _out;
    private boolean _growOutput;

    public ByteArrayEndPoint()
    {
        this(null, 0, null, null);
    }

    /**
     * @param input the input bytes
     * @param outputSize the output size
     */
    public ByteArrayEndPoint(byte[] input, int outputSize)
    {
        this(null, 0, input != null ? BufferUtil.toBuffer(input) : null, BufferUtil.allocate(outputSize));
    }

    /**
     * @param input the input string (converted to bytes using default encoding charset)
     * @param outputSize the output size
     */
    public ByteArrayEndPoint(String input, int outputSize)
    {
        this(null, 0, input != null ? BufferUtil.toBuffer(input) : null, BufferUtil.allocate(outputSize));
    }

    public ByteArrayEndPoint(Scheduler scheduler, long idleTimeoutMs)
    {
        this(scheduler, idleTimeoutMs, null, null);
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, byte[] input, int outputSize)
    {
        this(timer, idleTimeoutMs, input != null ? BufferUtil.toBuffer(input) : null, BufferUtil.allocate(outputSize));
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, String input, int outputSize)
    {
        this(timer, idleTimeoutMs, input != null ? BufferUtil.toBuffer(input) : null, BufferUtil.allocate(outputSize));
    }

    public ByteArrayEndPoint(Scheduler timer, long idleTimeoutMs, ByteBuffer input, ByteBuffer output)
    {
        super(timer);
        if (BufferUtil.hasContent(input))
            addInput(input);
        _out = output == null ? BufferUtil.allocate(1024) : output;
        setIdleTimeout(idleTimeoutMs);
        onOpen();
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
    public InetSocketAddress getLocalAddress()
    {
        return NOIPPORT;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return NOIPPORT;
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
        try (AutoLock lock = _lock.lock())
        {
            if (!isOpen())
                throw new ClosedChannelException();

            ByteBuffer in = _inQ.peek();
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
        try (AutoLock lock = _lock.lock())
        {
            if (isEOF(_inQ.peek()))
                throw new RuntimeIOException(new EOFException());
            boolean wasEmpty = _inQ.isEmpty();
            if (in == null)
            {
                _inQ.add(EOF);
                fillable = true;
            }
            if (BufferUtil.hasContent(in))
            {
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

    public void addInputAndExecute(ByteBuffer in)
    {
        boolean fillable = false;
        try (AutoLock lock = _lock.lock())
        {
            if (isEOF(_inQ.peek()))
                throw new RuntimeIOException(new EOFException());
            boolean wasEmpty = _inQ.isEmpty();
            if (in == null)
            {
                _inQ.add(EOF);
                fillable = true;
            }
            if (BufferUtil.hasContent(in))
            {
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
        try (AutoLock lock = _lock.lock())
        {
            return _out;
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
        return BufferUtil.toString(_out, charset);
    }

    /**
     * @return Returns the out.
     */
    public ByteBuffer takeOutput()
    {
        ByteBuffer b;

        try (AutoLock lock = _lock.lock())
        {
            b = _out;
            _out = BufferUtil.allocate(b.capacity());
        }
        getWriteFlusher().completeWrite();
        return b;
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
        ByteBuffer b;

        try (AutoLock l = _lock.lock())
        {
            while (BufferUtil.isEmpty(_out) && !isOutputShutdown())
            {
                if (!_hasOutput.await(time, unit))
                    return null;
            }
            b = _out;
            _out = BufferUtil.allocate(b.capacity());
        }
        getWriteFlusher().completeWrite();
        return b;
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
    public void setOutput(ByteBuffer out)
    {
        try (AutoLock lock = _lock.lock())
        {
            _out = out;
        }
        getWriteFlusher().completeWrite();
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
        try (AutoLock lock = _lock.lock())
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
        try (AutoLock l = _lock.lock())
        {
            if (!isOpen())
                throw new IOException("CLOSED");
            if (isOutputShutdown())
                throw new IOException("OSHUT");

            boolean idle = true;

            for (ByteBuffer b : buffers)
            {
                if (BufferUtil.hasContent(b))
                {
                    if (_growOutput && b.remaining() > BufferUtil.space(_out))
                    {
                        BufferUtil.compact(_out);
                        if (b.remaining() > BufferUtil.space(_out))
                        {
                            ByteBuffer n = BufferUtil.allocate(_out.capacity() + b.remaining() * 2);
                            BufferUtil.append(n, _out);
                            _out = n;
                        }
                    }

                    if (BufferUtil.append(_out, b) > 0)
                        idle = false;

                    if (BufferUtil.hasContent(b))
                    {
                        flushed = false;
                        break;
                    }
                }
            }
            if (!idle)
            {
                notIdle();
                _hasOutput.signalAll();
            }
        }
        return flushed;
    }

    @Override
    public void reset()
    {
        try (AutoLock l = _lock.lock())
        {
            _inQ.clear();
            _hasOutput.signalAll();
            BufferUtil.clear(_out);
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
        return _growOutput;
    }

    /**
     * @param growOutput the growOutput to set
     */
    public void setGrowOutput(boolean growOutput)
    {
        _growOutput = growOutput;
    }

    @Override
    public String toString()
    {
        int q;
        ByteBuffer b;
        String o;
        try (AutoLock lock = _lock.lock())
        {
            q = _inQ.size();
            b = _inQ.peek();
            o = BufferUtil.toDetailString(_out);
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
