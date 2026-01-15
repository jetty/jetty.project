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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.common.parser.ExtensionParser;

public class QuicTransportParametersExtensionParser implements ExtensionParser
{
    private final VarLenInt varLenInt = new VarLenInt();
    private final ExtensionParser.Listener listener;
    private TransportParameters transportParameters;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private long id;
    private TransportParameters.Id<?> paramId;
    private int length;
    private byte[] value;
    private int cursor;

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
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    transportParameters = new TransportParameters();
                    if (remaining > 1)
                    {
                        totalLength = (byteBuffer.getShort() & 0xFFFF);
                        listLength = totalLength;
                        state = State.ID;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        listLength = totalLength;
                        state = State.ID;
                    }
                }
                case ID ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        listLength -= l;
                        id = v;
                    }))
                    {
                        state = State.LENGTH;
                    }
                }
                case LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        listLength -= l;
                        length = (int)v;
                    }))
                    {
                        state = State.VALUE;
                    }
                }
                case VALUE ->
                {
                    paramId = TransportParameters.Ids.get(id);
                    if (paramId == null)
                    {
                        state = State.SKIP;
                    }
                    else
                    {
                        if (remaining >= length)
                        {
                            listLength -= length;
                            int result = switch (paramId)
                            {
                                case TransportParameters.LongId longId ->
                                {
                                    long paramValue = VarLenInt.decodeLong(byteBuffer);
                                    yield transportParameterComplete(longId, paramValue);
                                }
                                case TransportParameters.BytesId bytesId ->
                                {
                                    byte[] paramValue = new byte[length];
                                    byteBuffer.get(paramValue);
                                    yield transportParameterComplete(bytesId, paramValue);
                                }
                            };
                            if (result > 0)
                                return result;
                        }
                        else
                        {
                            value = new byte[length];
                            cursor = length;
                            state = State.VALUE_BYTES;
                        }
                    }
                }
                case VALUE_BYTES ->
                {
                    value[length - cursor] = byteBuffer.get();
                    --cursor;
                    if (cursor == 0)
                    {
                        listLength -= length;
                        int result = switch (paramId)
                        {
                            case TransportParameters.LongId longId ->
                            {
                                long paramValue = VarLenInt.decodeLong(ByteBuffer.wrap(value));
                                yield transportParameterComplete(longId, paramValue);
                            }
                            case TransportParameters.BytesId bytesId -> transportParameterComplete(bytesId, value);
                        };
                        if (result > 0)
                            return result;
                    }
                }
                case SKIP ->
                {
                    if (remaining >= length)
                    {
                        byteBuffer.position(byteBuffer.position() + length);
                        listLength -= length;
                        int result = transportParameterComplete(null, null);
                        if (result > 0)
                            return result;
                    }
                    else
                    {
                        cursor = length;
                        state = State.SKIP_BYTES;
                    }
                }
                case SKIP_BYTES ->
                {
                    byteBuffer.position(byteBuffer.position() + 1);
                    --cursor;
                    if (cursor == 0)
                    {
                        listLength -= length;
                        int result = transportParameterComplete(null, null);
                        if (result > 0)
                            return result;
                    }
                }
            }
        }
    }

    private <T> int transportParameterComplete(TransportParameters.Id<T> tpId, T tpValue)
    {
        if (tpId != null)
            transportParameters.put(tpId, tpValue);
        id = 0;
        paramId = null;
        length = 0;
        value = null;
        if (listLength == 0)
        {
            QuicTransportParametersExtension extension = new QuicTransportParametersExtension(transportParameters);
            transportParameters = null;
            int result = 2 + totalLength;
            totalLength = 0;
            state = State.TOTAL_LENGTH;
            listener.onExtension(extension);
            return result;
        }
        else
        {
            state = State.ID;
            return -1;
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        ID,
        LENGTH,
        VALUE,
        VALUE_BYTES,
        SKIP,
        SKIP_BYTES
    }
}
