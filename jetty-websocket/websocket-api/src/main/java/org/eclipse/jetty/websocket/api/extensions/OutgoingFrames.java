//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api.extensions;

import org.eclipse.jetty.websocket.api.WriteCallback;

/**
 * Interface for dealing with frames outgoing to the network (eventually)
 */
public interface OutgoingFrames
{
    /**
     * A frame, and optional callback, intended for the network.
     * <p>
     * Note: the frame can undergo many transformations in the various layers and extensions present in the implementation.
     * <p>
     * If you are implementing a mutation, you are obliged to handle the incoming WriteCallback appropriately.
     * 
     * @param frame
     *            the frame to eventually write to the network.
     * @param callback
     *            the optional callback to use for success/failure of the network write operation. Can be null.
     */
    void outgoingFrame(Frame frame, WriteCallback callback);
}
