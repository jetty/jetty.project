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

package org.eclipse.jetty.tls.common.generator;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class MessageGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(MessageGenerator.class);

    private final List<Listener> listeners = new ArrayList<>();
    private final ByteBufferPool bufferPool;
    private final boolean useDirectBuffers;

    protected MessageGenerator(ByteBufferPool bufferPool, boolean useDirectBuffers)
    {
        this.bufferPool = bufferPool;
        this.useDirectBuffers = useDirectBuffers;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public boolean isUseDirectBuffers()
    {
        return useDirectBuffers;
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    protected void notifyMessageGenerated(Message message)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onMessageGenerated(message);
            }
            catch (Throwable x)
            {
                LOG.info("failure while notifying listener {}", listener, x);
            }
        }
    }

    public abstract void generate(BufferAccumulator accumulator, Message message) throws Exception;

    public interface Listener
    {
        void onMessageGenerated(Message message);
    }
}
