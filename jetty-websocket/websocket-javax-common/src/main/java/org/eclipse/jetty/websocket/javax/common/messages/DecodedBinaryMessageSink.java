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

import java.lang.invoke.WrongMethodTypeException;
import java.nio.ByteBuffer;
import java.util.List;
import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.internal.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.core.internal.util.MethodHolder;
import org.eclipse.jetty.websocket.javax.common.decoders.RegisteredDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecodedBinaryMessageSink<T> extends AbstractDecodedMessageSink.Basic<Decoder.Binary<T>>
{
    private static final Logger LOG = LoggerFactory.getLogger(DecodedBinaryMessageSink.class);

    public DecodedBinaryMessageSink(CoreSession session, MethodHolder methodHolder, List<RegisteredDecoder> decoders)
    {
        super(session, methodHolder, decoders);
    }

    @Override
    MessageSink newMessageSink(CoreSession coreSession) throws Exception
    {
        MethodHolder methodHolder = args ->
        {
            if (args.length != 1)
                throw new WrongMethodTypeException(String.format("Expected %s params but had %s", 1, args.length));
            onWholeMessage((ByteBuffer)args[0]);
            return null;
        };

        return new ByteBufferMessageSink(coreSession, methodHolder);
    }

    public void onWholeMessage(ByteBuffer wholeMessage)
    {
        for (Decoder.Binary<T> decoder : _decoders)
        {
            if (decoder.willDecode(wholeMessage))
            {
                try
                {
                    T obj = decoder.decode(wholeMessage);
                    invoke(obj);
                    return;
                }
                catch (DecodeException e)
                {
                    throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Unable to decode", e);
                }
            }
        }

        LOG.warn("Message lost, willDecode() has returned false for all decoders in the decoder list.");
    }
}
