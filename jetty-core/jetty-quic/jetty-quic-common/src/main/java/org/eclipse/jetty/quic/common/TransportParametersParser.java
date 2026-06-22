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

import java.nio.ByteBuffer;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

public class TransportParametersParser
{
    private final VarLenInt varLenInt;
    private State state = State.LENGTH;
    private int cursor;
    private int length;
    private TransportParameters parameters;
    private TransportParameters.Id<?> parameterId;
    private int parameterLength;
    private byte[] parameterValue;

    public TransportParametersParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    public TransportParameters parse(ByteBuffer byteBuffer)
    {
        while (true)
        {
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;

            switch (state)
            {
                case LENGTH ->
                {
                    if (remaining > 1)
                    {
                        length = (byteBuffer.getShort() & 0xFFFF);
                        parameters = new TransportParameters();
                        state = State.PARAMETER_ID;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.LENGTH_BYTES;
                    }
                }
                case LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    length += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        parameters = new TransportParameters();
                        state = State.PARAMETER_ID;
                    }
                }
                case PARAMETER_ID ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        length -= l;
                        parameterId = convert(v);
                    }))
                    {
                        if (parameterId == null)
                            throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_id");
                        if (length <= 0)
                            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_transport_parameters_length");
                        state = State.PARAMETER_LENGTH;
                    }
                }
                case PARAMETER_LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        length -= l;
                        parameterLength = (int)v;
                    }))
                    {
                        if (length < 0)
                            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_transport_parameters_length");
                        parameterValue = new byte[parameterLength];
                        if (length == 0)
                        {
                            if (parameterLength != 0)
                                throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_transport_parameters_length");
                            store(parameterId, parameterValue);
                            return result();
                        }
                        state = State.PARAMETER_VALUE;
                    }
                }
                case PARAMETER_VALUE ->
                {
                    if (remaining >= parameterLength)
                    {
                        byteBuffer.get(parameterValue);
                        store(parameterId, parameterValue);
                        length -= parameterLength;
                        if (length == 0)
                            return result();
                        state = State.PARAMETER_ID;
                    }
                    else
                    {
                        int offset = parameterValue.length - parameterLength;
                        byteBuffer.get(parameterValue, offset, remaining);
                        parameterLength -= remaining;
                    }
                }
            }
        }
    }

    private void store(TransportParameters.Id<?> parameterId, byte[] parameterValue)
    {
        switch (parameterId)
        {
            case TransportParameters.LongId longId ->
                varLenInt.tryDecode(ByteBuffer.wrap(parameterValue), v -> parameters.put(longId, v));
            case TransportParameters.BytesId bytesId ->
                parameters.put(bytesId, parameterValue);
            // TODO: preferred_address and version_information.
         }
    }

    private TransportParameters result()
    {
        TransportParameters result = parameters;
        state = State.LENGTH;
        length = 0;
        parameters = null;
        parameterId = null;
        parameterLength = 0;
        parameterValue = null;
        return result;
    }

    private TransportParameters.Id<?> convert(long parameterId)
    {
        TransportParameters.Id<?> id = TransportParameters.Ids.get(parameterId);
        if (id != null)
            return id;
        // RFC-9000[18.1]: either a grease id, or an unknown id.
        // In both cases the parameter must be kept since it is part
        // of TLS messages that are used to derive cryptographic keys.
        return TransportParameters.Ids.create(parameterId, TransportParameters.BytesId::new);
    }

    private enum State
    {
        LENGTH,
        LENGTH_BYTES,
        PARAMETER_ID,
        PARAMETER_LENGTH,
        PARAMETER_VALUE
    }
}
