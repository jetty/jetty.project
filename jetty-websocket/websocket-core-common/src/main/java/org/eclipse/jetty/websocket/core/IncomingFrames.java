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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

/**
 * Interface for dealing with Incoming Frames.
 */
public interface IncomingFrames
{
    /**
     * Process the incoming frame.
     * <p>
     * Note: if you need to hang onto any information from the frame, be sure
     * to copy it, as the information contained in the Frame will be released
     * and/or reused by the implementation.
     *
     * @param frame the frame to process
     * @param callback the read completion
     */
    void onFrame(Frame frame, Callback callback);
}
