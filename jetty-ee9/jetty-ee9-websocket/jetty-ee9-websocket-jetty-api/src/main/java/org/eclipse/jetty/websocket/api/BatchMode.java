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

package org.eclipse.jetty.websocket.api;

/**
 * The possible batch modes when enqueuing outgoing frames.
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
