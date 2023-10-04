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

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.WrongMethodTypeException;
import java.util.List;
import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.internal.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.core.internal.util.MethodHolder;
import org.eclipse.jetty.websocket.javax.common.decoders.RegisteredDecoder;

public class DecodedBinaryStreamMessageSink<T> extends AbstractDecodedMessageSink.Stream<Decoder.BinaryStream<T>>
{
    public DecodedBinaryStreamMessageSink(CoreSession session, MethodHolder methodHolder, List<RegisteredDecoder> decoders)
    {
        super(session, methodHolder, decoders);
    }

    @Override
    MessageSink newMessageSink(CoreSession coreSession)
    {
        MethodHolder methodHolder = args ->
        {
            if (args.length != 1)
                throw new WrongMethodTypeException(String.format("Expected %s params but had %s", 1, args.length));
            onStreamStart((InputStream)args[0]);
            return null;
        };

        return new InputStreamMessageSink(coreSession, methodHolder);
    }

    public void onStreamStart(InputStream stream)
    {
        try
        {
            T obj = _decoder.decode(stream);
            invoke(obj);
        }
        catch (DecodeException | IOException e)
        {
            throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Unable to decode", e);
        }
    }
}
