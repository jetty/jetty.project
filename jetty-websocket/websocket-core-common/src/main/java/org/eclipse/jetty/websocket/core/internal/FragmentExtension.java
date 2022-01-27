//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fragment Extension
 */
public class FragmentExtension extends AbstractExtension implements DemandChain
{
    private static final Logger LOG = LoggerFactory.getLogger(FragmentExtension.class);

    private final FragmentingFlusher outgoingFlusher;
    private final DemandingFlusher incomingFlusher;
    private final Configuration configuration = new Configuration.ConfigurationCustomizer();

    public FragmentExtension()
    {
        outgoingFlusher = new FragmentingFlusher(configuration)
        {
            @Override
            void forwardFrame(Frame frame, Callback callback, boolean batch)
            {
                nextOutgoingFrame(frame, callback, batch);
            }
        };

        incomingFlusher = new FragmentingDemandingFlusher();
    }

    @Override
    public void demand(long n)
    {
        incomingFlusher.demand(n);
    }

    @Override
    public void setNextDemand(LongConsumer nextDemand)
    {
        incomingFlusher.setNextDemand(nextDemand);
    }

    @Override
    public String getName()
    {
        return "fragment";
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        incomingFlusher.onFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        outgoingFlusher.sendFrame(frame, callback, batch);
    }

    @Override
    public void init(ExtensionConfig config, WebSocketComponents components)
    {
        super.init(config, components);
        int maxLength = config.getParameter("maxLength", -1);
        configuration.setMaxFrameSize(maxLength);
    }

    public class FragmentingDemandingFlusher extends DemandingFlusher
    {
        public FragmentingDemandingFlusher()
        {
            super(FragmentExtension.this::nextIncomingFrame);
        }

        @Override
        protected boolean handle(Frame frame, Callback callback, boolean first)
        {
            if (first)
            {
                if (OpCode.isControlFrame(frame.getOpCode()))
                {
                    emitFrame(frame, callback);
                    return true;
                }
            }

            ByteBuffer payload = frame.getPayload();
            int remaining = payload.remaining();
            long maxFrameSize = configuration.getMaxFrameSize();
            int fragmentSize = (int)Math.min(remaining, maxFrameSize);

            boolean continuation = (frame.getOpCode() == OpCode.CONTINUATION) || !first;
            Frame fragment = new Frame(continuation ? OpCode.CONTINUATION : frame.getOpCode());
            boolean finished = (maxFrameSize <= 0 || remaining <= maxFrameSize);
            fragment.setFin(frame.isFin() && finished);

            if (finished)
            {
                // If finished we don't need to fragment, forward original payload.
                fragment.setPayload(payload);
            }
            else
            {
                // Slice the fragmented payload from the buffer.
                int limit = payload.limit();
                int newLimit = payload.position() + fragmentSize;
                payload.limit(newLimit);
                ByteBuffer payloadFragment = payload.slice();
                payload.limit(limit);
                fragment.setPayload(payloadFragment);
                payload.position(newLimit);
                if (LOG.isDebugEnabled())
                    LOG.debug("Fragmented {}->{}", frame, fragment);
            }

            Callback payloadCallback = Callback.from(() ->
            {
                if (finished)
                    callback.succeeded();
            }, t ->
            {
                // This is wrapped with CountingCallback so will only be failed once.
                callback.failed(t);
                failFlusher(t);
            });

            emitFrame(fragment, payloadCallback);
            return finished;
        }
    }
}
