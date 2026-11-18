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

import org.eclipse.jetty.util.Callback;

/**
 * Interface for dealing with incoming WebSocket frames,
 * from the network to upper layers and eventually the application.
 */
public interface IncomingFrames
{
    /**
     * <p>Processes the incoming frame.</p>
     * <p>Implementations are free to transform the frame, for example
     * by splitting it or decompressing its payload.</p>
     * <p>Transforming the frame may require to handle the {@link Callback}
     * accordingly: splitting may require to forward different callback
     * for each split frame, failing the original one as soon as a split
     * callback fails, but succeeding the original one only when the last
     * split callback succeeds.</p>
     * <p>Failure of the callback will propagate the failure back to the {@link CoreSession}
     * to fail the connection and attempt to send a close {@link Frame} if one has not been sent.</p>
     *
     * @param frame the frame to process.
     * @param callback the read completion.
     */
    void onFrame(Frame frame, Callback callback);
}
