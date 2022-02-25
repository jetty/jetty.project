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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

/**
 * Interface for dealing with frames outgoing to (eventually) the network layer.
 */
public interface OutgoingFrames
{
    /**
     * A frame, and optional callback, intended for the network layer.
     * <p>
     * Note: the frame can undergo many transformations in the various
     * layers and extensions present in the implementation.
     * <p>
     * If you are implementing a mutation, you are obliged to handle
     * the incoming WriteCallback appropriately.
     *
     * @param frame the frame to eventually write to the network layer.
     * @param callback the callback to notify when the frame is written.
     * @param batch the batch mode requested by the sender.
     */
    void sendFrame(Frame frame, Callback callback, boolean batch);
}
