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

package org.eclipse.jetty.quic.tls.internal.generator;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Message;

public abstract class MessageGenerator
{
    private final ByteBufferPool bufferPool;

    protected MessageGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public abstract void generate(RetainableByteBuffer.Mutable accumulator, Message message);
}
