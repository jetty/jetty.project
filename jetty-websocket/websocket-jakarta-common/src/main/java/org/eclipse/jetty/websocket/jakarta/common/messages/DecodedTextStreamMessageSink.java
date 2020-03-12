//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.common.messages;

import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import jakarta.websocket.CloseReason;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.util.messages.MessageSink;
import org.eclipse.jetty.websocket.util.messages.ReaderMessageSink;

public class DecodedTextStreamMessageSink<T> extends DecodedMessageSink<Decoder.TextStream<T>>
{
    public DecodedTextStreamMessageSink(CoreSession session,
                                        Decoder.TextStream<T> decoder,
                                        MethodHandle methodHandle)
        throws NoSuchMethodException, IllegalAccessException
    {
        super(session, decoder, methodHandle);
    }

    @Override
    protected MethodHandle newRawMethodHandle() throws NoSuchMethodException, IllegalAccessException
    {
        return MethodHandles.lookup().findVirtual(DecodedTextStreamMessageSink.class,
            "onStreamStart", MethodType.methodType(void.class, Reader.class))
            .bindTo(this);
    }

    @Override
    protected MessageSink newRawMessageSink(CoreSession session, MethodHandle rawMethodHandle)
    {
        return new ReaderMessageSink(session, rawMethodHandle);
    }

    @SuppressWarnings("Duplicates")
    public void onStreamStart(Reader reader)
    {
        try
        {
            T obj = getDecoder().decode(reader);
            methodHandle.invoke(obj);
        }
        catch (DecodeException e)
        {
            throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Unable to decode", e);
        }
        catch (Throwable t)
        {
            throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Endpoint notification error", t);
        }
    }
}
