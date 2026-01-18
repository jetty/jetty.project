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

import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.common.generator.ExtensionGenerator;
import org.eclipse.jetty.tls.ext.Extension;

public class QuicTransportParametersExtensionGenerator implements ExtensionGenerator
{
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
        TransportParameters parameters = extension.parameters();
        int totalLength = 0;
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : parameters)
        {
            TransportParameters.Id<?> id = entry.getKey();
            // The number of bytes necessary to encode the ID.
            // For example, MAX_IDLE_TIMEOUT = 1, it is encoded as 1, and its length is 1 byte.
            // For a GREASE ID such as 0xFF02DE1A, is it encoded as 0xC0000000FF02DE1A, and its length is 8 bytes.
            totalLength += VarLenInt.length(id.id());
            // The value can be a long or byte[], we need to know the length of the encoding of the value.
            // For example, the long value 0xFF02DE1A is encoded as 0xC0000000FF02DE1A, in 8 bytes.
            // For example, the connection ids are byte[] that are encoded in the byte[] itself, in array.length bytes.
            int valueEncodingLength = switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.length(parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parameters.get(bytesId).length;
            };
            // The number of bytes necessary to encode the length of the encoding of the value.
            totalLength += VarLenInt.length(valueEncodingLength);
            // The length of the encoding of the value.
            totalLength += valueEncodingLength;
        }
        accumulator.putShort((short)totalLength);
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : parameters)
        {
            TransportParameters.Id<?> id = entry.getKey();
            VarLenInt.encode(accumulator, id.id());
            int valueEncodingLength = switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.length(parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parameters.get(bytesId).length;
            };
            VarLenInt.encode(accumulator, valueEncodingLength);
            switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.encode(accumulator, parameters.get(longId));
                case TransportParameters.BytesId bytesId -> accumulator.put(parameters.get(bytesId));
            }
        }
        return 2 + 2 + totalLength;
    }
}
