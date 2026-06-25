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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.TransportParametersGenerator;
import org.eclipse.jetty.quic.common.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.tls.common.generator.ExtensionGenerator;
import org.eclipse.jetty.tls.ext.Extension;

public class QuicTransportParametersExtensionGenerator implements ExtensionGenerator
{
    private final TransportParametersGenerator generator = new TransportParametersGenerator();

    @Override
    public int type()
    {
        return QuicTransportParametersExtension.CODE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (QuicTransportParametersExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, QuicTransportParametersExtension extension)
    {
        accumulator.putShort((short)extension.code());
        TransportParameters parameters = extension.transportParameters();
        int totalLength = generator.generate(accumulator, parameters);
        return 2 + totalLength;
    }
}
