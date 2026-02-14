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
    public static int generate(RetainableByteBuffer.Mutable accumulator, TransportParameters parameters)
    {
        int totalLength = 0;
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : parameters)
        {
            TransportParameters.Id<?> id = entry.getKey();
            totalLength += VarLenInt.encode(accumulator, id.id());
            int valueLength = switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.length(parameters.get(longId));
                case TransportParameters.BytesId bytesId -> parameters.get(bytesId).length;
            };
            totalLength += VarLenInt.encode(accumulator, valueLength);
            switch (id)
            {
                case TransportParameters.LongId longId -> VarLenInt.encode(accumulator, parameters.get(longId));
                case TransportParameters.BytesId bytesId -> accumulator.put(parameters.get(bytesId));
            }
            totalLength += valueLength;
        }
        return totalLength;
    }
}
