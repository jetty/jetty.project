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

package org.eclipse.jetty.websocket.core.messages;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;

/**
 * <p>A consumer of WebSocket data frames (either BINARY or TEXT).</p>
 * <p>{@link FrameHandler} delegates the processing of data frames
 * to {@link MessageSink}, including the processing of the demand
 * for the next frames.</p>
 */
public interface MessageSink
{
    /**
     * <p>Consumes the WebSocket frame, possibly asynchronously
     * when this method has returned.</p>
     * <p>The callback argument must be completed when the frame
     * payload is consumed.</p>
     * <p>The demand for more frames must be explicitly invoked,
     * or arranged to be invoked asynchronously, by the implementation
     * of this method, by calling {@link CoreSession#demand(long)}.</p>
     *
     * @param frame the frame to consume
     * @param callback the callback to complete when the frame is consumed
     */
    void accept(Frame frame, Callback callback);

    /**
     * <p>Fails this {@link MessageSink} with the given cause.</p>
     *
     * @param failure the cause of the failure
     */
    default void fail(Throwable failure)
    {
    }
}
