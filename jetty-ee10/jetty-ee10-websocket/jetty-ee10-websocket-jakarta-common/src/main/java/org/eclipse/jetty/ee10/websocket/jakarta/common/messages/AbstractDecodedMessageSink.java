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

package org.eclipse.jetty.ee10.websocket.jakarta.common.messages;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.messages.MessageSink;
import org.eclipse.jetty.websocket.core.util.MethodHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDecodedMessageSink implements MessageSink
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractDecodedMessageSink.class);

    private final MethodHolder _methodHolder;
    private final MessageSink _messageSink;

    public AbstractDecodedMessageSink(CoreSession coreSession, MethodHolder methodHolder)
    {
        _methodHolder = methodHolder;

        try
        {
            _messageSink = newMessageSink(coreSession);
        }
        catch (Exception e)
        {
            // Throwing from here is an error implementation of the DecodedMessageSink.
            throw new RuntimeException(e);
        }
    }

    /**
     * Invoke the MessageSink with the decoded message.
     * @param message the decoded message.
     */
    void invoke(Object message)
    {
        try
        {
            _methodHolder.invoke(message);
        }
        catch (Throwable t)
        {
            throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Endpoint notification error", t);
        }
    }

    /**
     * @return a message sink which will first decode the message then pass it to {@link #_methodHolder}.
     * @throws Exception for any error in creating the message sink.
     */
    abstract MessageSink newMessageSink(CoreSession coreSession) throws Exception;

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting frame {} for {}", frame, _messageSink);
        _messageSink.accept(frame, callback);
    }

    @Override
    public void fail(Throwable failure)
    {
        _messageSink.fail(failure);
    }

    public abstract static class Basic<T extends Decoder> extends AbstractDecodedMessageSink
    {
        protected final List<T> _decoders;

        public Basic(CoreSession coreSession, MethodHolder methodHolder, List<RegisteredDecoder> decoders)
        {
            super(coreSession, methodHolder);
            if (decoders.isEmpty())
                throw new IllegalArgumentException("Require at least one decoder for " + this.getClass());
            _decoders = decoders.stream()
                .map(RegisteredDecoder::<T>getInstance)
                .collect(Collectors.toList());
        }
    }

    public abstract static class Stream<T extends Decoder> extends AbstractDecodedMessageSink
    {
        protected final T _decoder;

        public Stream(CoreSession coreSession, MethodHolder methodHolder, List<RegisteredDecoder> decoders)
        {
            super(coreSession, methodHolder);
            if (decoders.size() != 1)
                throw new IllegalArgumentException("Require exactly one decoder for " + this.getClass());
            _decoder = decoders.get(0).getInstance();
        }
    }
}
