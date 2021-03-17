//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ReadPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerDatagramEndPoint extends IdleTimeout implements EndPoint, ManagedSelector.Selectable
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerDatagramEndPoint.class);

    private final long createdTimeStamp = System.currentTimeMillis();
    private final AutoLock _lock = new AutoLock();
    private final Runnable _runFillable = new ServerDatagramEndPoint.RunnableCloseable("runFillable")
    {
        @Override
        public InvocationType getInvocationType()
        {
            return getFillInterest().getCallbackInvocationType();
        }

        @Override
        public void run()
        {
            getFillInterest().fillable();
        }
    };

    private final Runnable _runCompleteWrite = new ServerDatagramEndPoint.RunnableCloseable("runCompleteWrite")
    {
        @Override
        public InvocationType getInvocationType()
        {
            return getWriteFlusher().getCallbackInvocationType();
        }

        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s:%s->%s", ServerDatagramEndPoint.this, _operation, getInvocationType(), getWriteFlusher());
        }
    };

    private final Runnable _runCompleteWriteFillable = new ServerDatagramEndPoint.RunnableCloseable("runCompleteWriteFillable")
    {
        @Override
        public InvocationType getInvocationType()
        {
            InvocationType fillT = getFillInterest().getCallbackInvocationType();
            InvocationType flushT = getWriteFlusher().getCallbackInvocationType();
            if (fillT == flushT)
                return fillT;

            if (fillT == InvocationType.EITHER && flushT == InvocationType.NON_BLOCKING)
                return InvocationType.EITHER;

            if (fillT == InvocationType.NON_BLOCKING && flushT == InvocationType.EITHER)
                return InvocationType.EITHER;

            return InvocationType.BLOCKING;
        }

        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
            getFillInterest().fillable();
        }
    };

    private final FillInterest fillInterest = new FillInterest()
    {
        @Override
        protected void needsFillInterest()
        {
            changeInterests(SelectionKey.OP_READ);
        }
    };
    private final WriteFlusher writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlush()
        {
            changeInterests(SelectionKey.OP_WRITE);
        }
    };

    public FillInterest getFillInterest()
    {
        return fillInterest;
    }

    public WriteFlusher getWriteFlusher()
    {
        return writeFlusher;
    }

    private final ManagedSelector.SelectorUpdate _updateKeyAction = s -> updateKey();

    private final DatagramChannel channel;
    private final ManagedSelector _selector;
    private Connection connection;
    private boolean open;

    private SelectionKey _key;
    private boolean _updatePending;
    private int _currentInterestOps;
    private int _desiredInterestOps;

    public ServerDatagramEndPoint(Scheduler scheduler, DatagramChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        super(scheduler);
        this.channel = channel;
        this._selector = selector;
        this._key = selectionKey;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        try
        {
            return (InetSocketAddress)channel.getLocalAddress();
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return open;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return createdTimeStamp;
    }

    @Override
    public void shutdownOutput()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOutputShutdown()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInputShutdown()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close(Throwable cause)
    {
        LOG.info("closed endpoint");
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int pos = BufferUtil.flipToFill(buffer);

        buffer.position(pos + AddressCodec.ENCODED_ADDRESS_LENGTH);
        InetSocketAddress peer = (InetSocketAddress)channel.receive(buffer);
        if (peer == null)
        {
            buffer.position(pos);
            BufferUtil.flipToFlush(buffer, pos);
            return 0;
        }

        int finalPosition = buffer.position();
        buffer.position(pos);
        AddressCodec.encodeInetSocketAddress(buffer, peer);
        buffer.position(finalPosition);

        BufferUtil.flipToFlush(buffer, pos);

        return finalPosition - AddressCodec.ENCODED_ADDRESS_LENGTH;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        InetSocketAddress peer = AddressCodec.decodeInetSocketAddress(buffers[0]);
        for (int i = 1; i < buffers.length; i++)
        {
            ByteBuffer buffer = buffers[i];
            int sent = channel.send(buffer, peer);
            if (sent == 0)
                return false;
        }
        return true;
    }

    @Override
    public Object getTransport()
    {
        return this.channel;
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {
        // TODO: close the channel.
        LOG.info("idle timeout", timeout);
    }

    @Override
    public void updateKey()
    {
        // This method runs from the selector thread,
        // possibly concurrently with changeInterests(int).

        try
        {
            int oldInterestOps;
            int newInterestOps;
            try (AutoLock l = _lock.lock())
            {
                _updatePending = false;
                oldInterestOps = _currentInterestOps;
                newInterestOps = _desiredInterestOps;
                if (oldInterestOps != newInterestOps)
                {
                    _currentInterestOps = newInterestOps;
                    _key.interestOps(newInterestOps);
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Key interests updated {} -> {} on {}", oldInterestOps, newInterestOps, this);
        }
        catch (CancelledKeyException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring key update for cancelled key {}", this, x);
            close();
        }
        catch (Throwable x)
        {
            LOG.warn("Ignoring key update for {}", this, x);
            close();
        }
    }

    @Override
    public void replaceKey(SelectionKey newKey)
    {
        this._key = newKey;
    }

    private void changeInterests(int operation)
    {
        // This method runs from any thread, possibly
        // concurrently with updateKey() and onSelected().

        int oldInterestOps;
        int newInterestOps;
        boolean pending;
        try (AutoLock l = _lock.lock())
        {
            pending = _updatePending;
            oldInterestOps = _desiredInterestOps;
            newInterestOps = oldInterestOps | operation;
            if (newInterestOps != oldInterestOps)
                _desiredInterestOps = newInterestOps;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("changeInterests p={} {}->{} for {}", pending, oldInterestOps, newInterestOps, this);

        if (!pending && _selector != null)
            _selector.submit(_updateKeyAction);
    }

    @Override
    public Runnable onSelected()
    {
        // This method runs from the selector thread,
        // possibly concurrently with changeInterests(int).

        int readyOps = _key.readyOps();
        int oldInterestOps;
        int newInterestOps;
        try (AutoLock l = _lock.lock())
        {
            _updatePending = true;
            // Remove the readyOps, that here can only be OP_READ or OP_WRITE (or both).
            oldInterestOps = _desiredInterestOps;
            newInterestOps = oldInterestOps & ~readyOps;
            _desiredInterestOps = newInterestOps;
        }

        boolean fillable = (readyOps & SelectionKey.OP_READ) != 0;
        boolean flushable = (readyOps & SelectionKey.OP_WRITE) != 0;

        if (LOG.isDebugEnabled())
            LOG.debug("onSelected {}->{} r={} w={} for {}", oldInterestOps, newInterestOps, fillable, flushable, this);

        // return task to complete the job
        Runnable task = fillable
            ? (flushable
            ? _runCompleteWriteFillable
            : _runFillable)
            : (flushable
            ? _runCompleteWrite
            : null);

        if (LOG.isDebugEnabled())
            LOG.debug("task {}", task);
        return task;
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        fillInterest.register(callback);
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        return fillInterest.tryRegister(callback);
    }

    @Override
    public boolean isFillInterested()
    {
        return fillInterest.isInterested();
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        writeFlusher.write(callback, buffers);
    }

    @Override
    public Connection getConnection()
    {
        return connection;
    }

    @Override
    public void setConnection(Connection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        open = true;
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", this);
    }

    @Override
    public void onClose()
    {
        super.onClose();
        onClose(null);
    }

    @Override
    public void onClose(Throwable cause)
    {
        open = false;
        if (LOG.isDebugEnabled())
            LOG.debug("onClose {}", this);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        throw new UnsupportedOperationException();
    }

    private abstract class RunnableTask implements Runnable, Invocable
    {
        final String _operation;

        protected RunnableTask(String op)
        {
            _operation = op;
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s:%s", ServerDatagramEndPoint.this, _operation, getInvocationType());
        }
    }

    private abstract class RunnableCloseable extends ServerDatagramEndPoint.RunnableTask implements Closeable
    {
        protected RunnableCloseable(String op)
        {
            super(op);
        }

        @Override
        public void close()
        {
            try
            {
                ServerDatagramEndPoint.this.close();
            }
            catch (Throwable x)
            {
                LOG.warn("Unable to close {}", ServerDatagramEndPoint.this, x);
            }
        }
    }
}
