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

package org.eclipse.jetty.quic.common.tls.parser;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.common.TransportParametersParser;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.common.parser.ExtensionParser;

public class QuicTransportParametersExtensionParser implements ExtensionParser
{
    private final TransportParametersParser parser = new TransportParametersParser(new VarLenInt());
    private final ExtensionParser.Listener listener;

    public QuicTransportParametersExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return QuicTransportParametersExtension.CODE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        int remaining = byteBuffer.remaining();
        TransportParameters parameters = parser.parse(byteBuffer);
        if (parameters == null)
            throw new BufferUnderflowException();
        listener.onExtension(new QuicTransportParametersExtension(parameters));
        return 2 + (remaining - byteBuffer.remaining());
    }
}
