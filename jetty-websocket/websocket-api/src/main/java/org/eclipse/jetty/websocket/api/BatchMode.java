//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;

/**
 * The possible batch modes when invoking {@link OutgoingFrames#outgoingFrame(org.eclipse.jetty.websocket.api.extensions.Frame, WriteCallback, BatchMode)}.
 */
public enum BatchMode
{
    /**
     * Implementers are free to decide whether to send or not frames
     * to the network layer.
     */
    AUTO,

    /**
     * Implementers must batch frames.
     */
    ON,

    /**
     * Implementers must send frames to the network layer.
     */
    OFF;

    public static BatchMode max(BatchMode one, BatchMode two)
    {
        // Return the BatchMode that has the higher priority, where AUTO < ON < OFF.
        return one.ordinal() < two.ordinal() ? two : one;
    }
}
