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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingEntry;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.util.DemandChain;
import org.eclipse.jetty.websocket.core.util.FragmentingFlusher;
import org.eclipse.jetty.websocket.core.util.WebSocketDemander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fragment Extension
 */
public class FragmentExtension extends AbstractExtension implements DemandChain
{
    private static final Logger LOG = LoggerFactory.getLogger(FragmentExtension.class);

    private final FragmentingFlusher outgoingFlusher;
    private final WebSocketDemander incomingFlusher;
    private final Configuration configuration = new Configuration.ConfigurationCustomizer();

    public FragmentExtension()
    {
        outgoingFlusher = new FragmentingFlusher(configuration)
        {
            @Override
            protected void forwardFrame(OutgoingEntry entry)
            {
                nextOutgoingFrame(entry);
            }
        };

        incomingFlusher = new FragmentingDemander();
    }

    @Override
    public void demand()
    {
        incomingFlusher.demand();
    }

    @Override
    public void setNextDemand(DemandChain nextDemand)
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
    public void sendFrame(OutgoingEntry entry)
    {
        outgoingFlusher.sendFrame(entry);
    }

    @Override
    public void init(ExtensionConfig config, WebSocketComponents components)
    {
        super.init(config, components);
        int maxLength = config.getParameter("maxLength", -1);
        configuration.setMaxFrameSize(maxLength);
    }

    public class FragmentingDemander extends WebSocketDemander
    {
        private ByteBuffer _payload;

        public FragmentingDemander()
        {
            super(FragmentExtension.this::nextIncomingFrame);
        }

        @Override
        protected boolean handle(Frame frame, Callback callback, boolean first)
        {
            long maxFrameSize = configuration.getMaxFrameSize();
            if (first)
            {
                if (frame.isControlFrame() || maxFrameSize <= 0 || frame.getPayloadLength() <= maxFrameSize)
                {
                    emitFrame(frame, callback);
                    return true;
                }

                _payload = frame.getPayload();
            }

            int remaining = _payload.remaining();
            int fragmentSize = (int)Math.min(remaining, maxFrameSize);
            byte opCode = (frame.getOpCode() == OpCode.CONTINUATION || !first) ? OpCode.CONTINUATION : frame.getOpCode();
            Frame fragment = new Frame(opCode);
            boolean finished = (maxFrameSize <= 0 || remaining <= maxFrameSize);
            fragment.setFin(frame.isFin() && finished);

            if (finished)
            {
                // If finished we don't need to fragment, forward original payload.
                fragment.setPayload(_payload);
                _payload = null;
            }
            else
            {
                // Slice the fragmented payload from the buffer.
                int limit = _payload.limit();
                int newLimit = _payload.position() + fragmentSize;
                _payload.limit(newLimit);
                ByteBuffer payloadFragment = _payload.slice();
                _payload.limit(limit);
                fragment.setPayload(payloadFragment);
                _payload.position(newLimit);
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

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            super.onCompleteFailure(cause);
            _payload = null;
        }
    }
}
