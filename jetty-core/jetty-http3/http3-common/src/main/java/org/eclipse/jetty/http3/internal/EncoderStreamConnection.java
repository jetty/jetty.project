//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;

public class EncoderStreamConnection extends InstructionStreamConnection
{
    // SPEC: QPACK Encoder Stream Type.
    public static final long STREAM_TYPE = 0x02;

    private final QpackDecoder decoder;

    public EncoderStreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, QpackDecoder decoder)
    {
        super(endPoint, executor, byteBufferPool);
        this.decoder = decoder;
    }

    @Override
    protected void parseInstruction(ByteBuffer buffer) throws QpackException
    {
        decoder.parseInstructions(buffer);
    }
}
