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

package org.eclipse.jetty.websocket.core.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to split large data frames into multiple frames below the maxFrameSize.
 * Control frames and dataFrames smaller than the maxFrameSize will be forwarded
 * directly to {@link #forwardFrame(OutgoingEntry)}.
 */
public abstract class FragmentingFlusher extends WebSocketFlusher
{
    private static final Logger LOG = LoggerFactory.getLogger(FragmentingFlusher.class);
    private final Configuration _configuration;
    private ByteBuffer _payload;

    public FragmentingFlusher(Configuration configuration)
    {
        this._configuration = configuration;
    }

    protected abstract void forwardFrame(OutgoingEntry entry);

    @Override
    protected boolean onFrame(OutgoingEntry entry, boolean first)
    {
        Frame frame = entry.getFrame();
        long maxFrameSize = _configuration.getMaxFrameSize();
        if (first)
        {
            if (frame.isControlFrame() || maxFrameSize <= 0 || frame.getPayloadLength() <= maxFrameSize)
            {
                forwardFrame(entry);
                return true;
            }

            _payload = frame.getPayload().slice();
        }

        int remaining = _payload.remaining();
        int fragmentSize = (int)Math.min(remaining, maxFrameSize);
        byte opCode = (frame.getOpCode() == OpCode.CONTINUATION || !first) ? OpCode.CONTINUATION : frame.getOpCode();
        Frame fragment = new Frame(opCode);
        boolean finished = (maxFrameSize <= 0 || remaining <= maxFrameSize);
        fragment.setFin(frame.isFin() && finished);

        // If we don't need to fragment just forward with original payload.
        if (finished)
        {
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

        forwardFrame(new OutgoingEntry.Builder(entry).frame(fragment).build());
        return finished;
    }

    @Override
    protected void onCompleteFailure(Throwable cause)
    {
        super.onCompleteFailure(cause);
        _payload = null;
    }
}
