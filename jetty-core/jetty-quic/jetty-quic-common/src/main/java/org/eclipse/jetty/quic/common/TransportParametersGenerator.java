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

package org.eclipse.jetty.quic.common;

import java.util.Map;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class TransportParametersGenerator
{
    /// Returns the number of bytes of the encoded representation of the given [TransportParameters].
    ///
    /// The number includes 2 bytes to encode the total length, plus the sum of
    /// the bytes necessary to encode each id and its correspondent value of the
    /// [TransportParameters].
    ///
    /// @param parameters the [TransportParameters] to calculate the encoding length for
    /// @return the number of bytes of the encoded representation
    public static int length(TransportParameters parameters)
    {
        int length = 0;
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : parameters)
        {
            TransportParameters.Id<?> id = entry.getKey();
            length += VarLenInt.length(id.id());
            int valueLength = switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.length(parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parameters.get(bytesId).length;
            };
            length += VarLenInt.length(valueLength) + valueLength;
        }
        return 2 + length;
    }

    public int generate(RetainableByteBuffer.Mutable buffer, TransportParameters parameters)
    {
        int parametersLength = length(parameters) - 2;
        buffer.putShort((short)parametersLength);
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : parameters)
        {
            TransportParameters.Id<?> id = entry.getKey();
            VarLenInt.encode(buffer, id.id());
            int valueLength = switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.length(parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parameters.get(bytesId).length;
            };
            VarLenInt.encode(buffer, valueLength);
            switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.encode(buffer, parameters.get(longId));
                case TransportParameters.BytesId bytesId -> buffer.put(parameters.get(bytesId));
            }
        }
        return 2 + parametersLength;
    }
}
