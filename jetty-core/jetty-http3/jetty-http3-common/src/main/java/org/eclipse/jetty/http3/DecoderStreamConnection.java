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

import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class DecoderStreamConnection extends InstructionStreamConnection
{
    private final QpackEncoder encoder;

    public DecoderStreamConnection(EndPoint endPoint, Executor executor, WritableBufferPool bufferPool, QpackEncoder encoder, ParserListener listener)
    {
        super(endPoint, executor, bufferPool, listener);
        this.encoder = encoder;
    }

    @Override
    protected void parseInstruction(RetainableByteBuffer buffer) throws QpackException
    {
        encoder.parseInstructions(buffer);
    }
}
