//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.io.BatchMode;

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
     * @param frame     the frame to eventually write to the network layer.
     * @param callback  the callback to notify when the frame is written.
     * @param batchMode the batch mode requested by the sender.
     */
    // TODO: rename back to not break existing Custom Extensions
    // TODO: if we do break Custom Extensions, then this should be .enqueueFrame() or .offerFrame(), not .sendFrame()
    // TODO: alternate approach is to leave this for Extensions only to use, not using it websocket-core itself
    // TODO: a websocket-extensions-api module for a stable API would make sense then.
    void sendFrame(Frame frame, Callback callback, BatchMode batchMode);
}
