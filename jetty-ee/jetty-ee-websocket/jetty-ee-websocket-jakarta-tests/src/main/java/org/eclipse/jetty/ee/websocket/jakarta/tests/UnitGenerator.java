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

package org.eclipse.jetty.ee.websocket.jakarta.tests;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.internal.Generator;

/**
 * Extension of the default WebSocket Generator for unit testing purposes
 */
public class UnitGenerator extends Generator
{
    // Client side framing mask
    private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
    private final boolean applyMask;

    public UnitGenerator(Behavior behavior)
    {
        applyMask = (behavior == Behavior.CLIENT);
    }

    public void generate(ByteBuffer buffer, Frame frame)
    {
        if (applyMask)
            frame.setMask(MASK);
        generateWholeFrame(frame, buffer);
    }
}
