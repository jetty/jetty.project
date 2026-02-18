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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.util.VarLenInt;

public class TransportParametersGenerator
{
    public int generate(RetainableByteBuffer.Mutable accumulator, TransportParameters parameters)
    {
        RetainableByteBuffer.DynamicCapacity parametersAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);

        int totalLength = 0;
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : parameters)
        {
            TransportParameters.Id<?> id = entry.getKey();
            totalLength += VarLenInt.encode(parametersAccumulator, id.id());
            int valueLength = switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.length(parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parameters.get(bytesId).length;
            };
            totalLength += VarLenInt.encode(parametersAccumulator, valueLength);
            switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.encode(parametersAccumulator, parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parametersAccumulator.put(parameters.get(bytesId));
            }
            totalLength += valueLength;
        }

        accumulator.putShort((short)totalLength);
        accumulator.add(parametersAccumulator);

        return 2 + totalLength;
    }
}
