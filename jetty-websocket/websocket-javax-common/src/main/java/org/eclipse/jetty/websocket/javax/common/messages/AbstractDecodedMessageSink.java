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

package org.eclipse.jetty.websocket.javax.common.messages;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.stream.Collectors;
import javax.websocket.Decoder;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.javax.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.websocket.util.messages.MessageSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDecodedMessageSink<T extends Decoder> implements MessageSink
{
    protected final Logger _logger;
    protected final CoreSession _coreSession;
    protected final MethodHandle _methodHandle;
    protected final MessageSink _messageSink;
    protected final List<T> _decoders;

    public AbstractDecodedMessageSink(CoreSession coreSession, MethodHandle methodHandle, List<RegisteredDecoder> decoders)
    {
        _logger = LoggerFactory.getLogger(getClass());
        _coreSession = coreSession;
        _methodHandle = methodHandle;
        _decoders = decoders.stream()
            .map(RegisteredDecoder::<T>getInstance)
            .collect(Collectors.toList());

        try
        {
            _messageSink = getMessageSink();
        }
        catch (Exception e)
        {
            // Throwing from here is an error implementation of the DecodedMessageSink.
            throw new RuntimeException(e);
        }
    }

    /**
     * @return a message sink which will first decode the message then pass it to {@link #_methodHandle}.
     * @throws Exception for any error in creating the message sink.
     */
    abstract MessageSink getMessageSink() throws Exception;

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("accepting frame {} for {}", frame, _messageSink);
        _messageSink.accept(frame, callback);
    }
}
