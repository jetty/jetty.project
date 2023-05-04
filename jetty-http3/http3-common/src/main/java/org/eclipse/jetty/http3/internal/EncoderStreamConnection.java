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

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EncoderStreamConnection extends InstructionStreamConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(EncoderStreamConnection.class);

    // SPEC: QPACK Encoder Stream Type.
    public static final long STREAM_TYPE = 0x02;

    private final QpackDecoder decoder;
    private final ParserListener listener;

    public EncoderStreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, QpackDecoder decoder, ParserListener listener)
    {
        super(endPoint, executor, byteBufferPool);
        this.decoder = decoder;
        this.listener = listener;
    }

    @Override
    protected void parseInstruction(ByteBuffer buffer)
    {
        try
        {
            decoder.parseInstructions(buffer);
        }
        catch (QpackException.SessionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifySessionFailure(x.getErrorCode(), x.getMessage(), x);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifySessionFailure(HTTP3ErrorCode.INTERNAL_ERROR.code(), "internal_error", x);
        }
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
}
