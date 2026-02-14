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
    private TransportParameters parameters;
    private long frameType;
    private long length;
    private TransportParameters.Id<?> parameterId;
    private int parameterLength;
    private byte[] parameterValue;

    public TransportParametersParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    public TransportParameters parse(ByteBuffer byteBuffer)
    {
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> length = v))
                        state = State.PARAMETER_ID;
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
                            throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_id", frameType);
                        if (length <= 0)
                            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_transport_parameters_length", frameType);
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
                            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_transport_parameters_length", frameType);
                        parameterValue = new byte[parameterLength];
                        if (length == 0)
                        {
                            if (parameterLength != 0)
                                throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_transport_parameters_length", frameType);
                            store(parameterId, parameterValue);
                            return result();
                        }
                        state = State.PARAMETER_VALUE;
                    }
                }
                case PARAMETER_VALUE ->
                {
                    int remaining = byteBuffer.remaining();
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
        return null;
    }

    private void store(TransportParameters.Id<?> parameterId, byte[] parameterValue)
    {
        if (parameterId instanceof TransportParameters.LongId longId)
            varLenInt.tryDecode(ByteBuffer.wrap(parameterValue), v -> parameters.put(longId, v));
        else if (parameterId instanceof TransportParameters.BytesId bytesId)
            parameters.put(bytesId, parameterValue);
            // TODO: preferred address
        else
            throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "unsupported_transport_parameter_id", frameType);
    }

    private TransportParameters result()
    {
        TransportParameters result = parameters;
        state = State.LENGTH;
        parameters = null;
        frameType = 0;
        length = 0;
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
        // It is a grease id (RFC 9000, section 18.1).
        if (TransportParameters.Ids.isGrease(parameterId))
            return TransportParameters.Ids.create(parameterId, TransportParameters.BytesId::new);
        // Unknown id, bail out.
        return null;
    }

    private enum State
    {
        LENGTH, PARAMETER_ID, PARAMETER_LENGTH, PARAMETER_VALUE
    }
}
