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

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.parser.ControlParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlConnection extends AbstractConnection implements Connection.UpgradeTo
{
    // SPEC: Control Stream Type.
    public static final int STREAM_TYPE = 0x00;
    private static final Logger LOG = LoggerFactory.getLogger(ControlConnection.class);

    private final ByteBufferPool byteBufferPool;
    private final ControlParser parser;
    private final ParserListener listener;
    private boolean useInputDirectByteBuffers = true;
    private ByteBuffer buffer;

    public ControlConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, ControlParser parser, ParserListener listener)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.parser = parser;
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
        buffer = byteBufferPool.acquire(capacity, isUseInputDirectByteBuffers());
        int position = BufferUtil.flipToFill(buffer);
        buffer.put(upgrade);
        BufferUtil.flipToFlush(buffer, position);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (BufferUtil.hasContent(buffer))
            onFillable();
        else
            fillInterested();
    }

    @Override
    public void onFillable()
    {
        try
        {
            if (buffer == null)
                buffer = byteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());

            while (true)
            {
                // Parse first in case of bytes from the upgrade.
                while (buffer.hasRemaining())
                {
                    Frame frame = parser.parse(buffer);
                    if (frame == null)
                        break;
                    notifyFrame(frame);
                }

                // Then read from the EndPoint.
                int filled = getEndPoint().fill(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", filled, this);

                if (filled == 0)
                {
                    byteBufferPool.release(buffer);
                    buffer = null;
                    fillInterested();
                    break;
                }
                else if (filled < 0)
                {
                    byteBufferPool.release(buffer);
                    buffer = null;
                    getEndPoint().close();
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not process control stream {}", getEndPoint(), x);
            byteBufferPool.release(buffer);
            buffer = null;
            getEndPoint().close(x);
        }
    }

    private void notifyFrame(Frame frame)
    {
        FrameType frameType = frame.getFrameType();
        switch (frameType)
        {
            case SETTINGS:
            {
                notifySettings((SettingsFrame)frame);
                break;
            }
            default:
            {
                throw new UnsupportedOperationException("unsupported frame type " + frameType);
            }
        }
    }

    private void notifySettings(SettingsFrame frame)
    {
        try
        {
            listener.onSettings(frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }
}
