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

package org.eclipse.jetty.quic.common.tls.generator;

import org.eclipse.jetty.quic.common.TransportParametersGenerator;
import org.eclipse.jetty.quic.common.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.tls.common.generator.ExtensionGenerator;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class QuicTransportParametersExtensionGenerator extends ExtensionGenerator
{
    private final TransportParametersGenerator generator = new TransportParametersGenerator();

    public QuicTransportParametersExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers, QuicTransportParametersExtension.CODE);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (QuicTransportParametersExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, QuicTransportParametersExtension extension)
    {
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(2, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        buffer.putShort((short)extension.code());
        int totalLength = generator.generate(buffer, extension.transportParameters());
        return 2 + totalLength;
    }
}
