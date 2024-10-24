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

package org.eclipse.jetty.http3;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class InstructionStreamConnection extends AbstractConnection implements Connection.UpgradeTo
{
    private static final Logger LOG = LoggerFactory.getLogger(InstructionStreamConnection.class);

    private final Callback fillableCallback = new FillableCallback();
    private final ByteBufferPool bufferPool;
    private final ParserListener listener;
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer buffer;

    public InstructionStreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ParserListener listener)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.listener = listener;
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @Override
    public void onUpgradeTo(ByteBuffer upgrade)
    {
        int capacity = Math.max(upgrade.remaining(), getInputBufferSize());
        buffer = bufferPool.acquire(capacity, isUseInputDirectByteBuffers());
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        int position = BufferUtil.flipToFill(byteBuffer);
        byteBuffer.put(upgrade);
        BufferUtil.flipToFlush(byteBuffer, position);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (buffer != null && buffer.hasRemaining())
            onFillable();
        else
            setFillInterest();
    }

    @Override
    public void onFillable()
    {
        try
        {
            if (buffer == null)
                buffer = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            while (true)
            {
                // Parse first in case of bytes from the upgrade.
                parseInstruction(byteBuffer);

                // Then read from the EndPoint.
                int filled = getEndPoint().fill(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", filled, this);

                if (filled == 0)
                {
                    buffer.release();
                    buffer = null;
                    setFillInterest();
                    break;
                }
                else if (filled < 0)
                {
                    buffer.release();
                    buffer = null;
                    getEndPoint().close();
                    break;
                }
            }
        }
        catch (QpackException.SessionException x)
        {
            fail(x.getErrorCode(), x.getMessage(), x);
        }
        catch (Throwable x)
        {
            fail(HTTP3ErrorCode.INTERNAL_ERROR.code(), "internal_error", x);
        }
    }

    private void setFillInterest()
    {
        fillInterested(fillableCallback);
    }

    private void fail(long errorCode, String message, Throwable failure)
    {
        buffer.release();
        buffer = null;
        if (LOG.isDebugEnabled())
            LOG.debug("could not process instruction stream {}", getEndPoint(), failure);
        notifySessionFailure(errorCode, message, failure);
    }

    protected void notifySessionFailure(long error, String reason, Throwable failure)
    {
        try
        {
            listener.onSessionFailure(error, reason, failure);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    protected abstract void parseInstruction(ByteBuffer buffer) throws QpackException;

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
}
