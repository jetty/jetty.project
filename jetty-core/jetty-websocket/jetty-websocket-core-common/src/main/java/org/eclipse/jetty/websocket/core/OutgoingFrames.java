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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;

/**
 * Interface for dealing with outgoing WebSocket frames,
 * from the application to lower layers and eventually to the network.
 */
public interface OutgoingFrames
{
    /**
     * <p>Processes the outgoing frame.</p>
     * <p>Implementations are free to transform the frame, for example
     * by splitting it or compressing its payload.</p>
     * <p>Transforming the frame may require to handle the {@link Callback}
     * accordingly: splitting may require to forward different callback
     * for each split frame, failing the original one as soon as a split
     * callback fails, but succeeding the original one only when the last
     * split callback succeeds.</p>
     * <p>The {@link Frame}'s payload {@link ByteBuffer} is consumed by this call.</p>
     * <p>Failure of the callback will propagate the failure back to the {@link CoreSession}
     * to fail the connection and attempt to send a close {@link Frame} if one has not been sent.</p>
     *
     * @param frame the frame to eventually write to the network layer.
     * @param callback the callback to notify when the frame is written.
     * @param batch the batch mode requested by the sender.
     */
    default void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        sendFrame(new OutgoingEntry.Builder(frame, callback).batch(batch).build());
    }

    /**
     * <p>Sends an {@link OutgoingEntry} containing a {@link Frame} and {@link Callback}.</p>
     * <p>The {@link Frame}'s payload is consumed by this call.</p>
     *
     * @param entry the entry to eventually write to the network layer.
     */
    void sendFrame(OutgoingEntry entry);
}
