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

import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;

public class DecoderStreamConnection extends InstructionStreamConnection
{
    // SPEC: QPACK Encoder Stream Type.
    public static final long STREAM_TYPE = 0x03;

    private final QpackEncoder encoder;

    public DecoderStreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, QpackEncoder encoder)
    {
        super(endPoint, executor, byteBufferPool);
        this.encoder = encoder;
    }

    @Override
    protected void parseInstruction(ByteBuffer buffer) throws QpackException
    {
        encoder.parseInstructions(buffer);
    }
}
