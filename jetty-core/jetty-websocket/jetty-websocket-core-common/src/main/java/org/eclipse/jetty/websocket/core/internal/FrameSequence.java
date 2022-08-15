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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;

public class FrameSequence
{
    private final AutoLock lock = new AutoLock();
    private byte state = OpCode.UNDEFINED;

    public void check(byte opcode, boolean fin) throws ProtocolException
    {
        try (AutoLock l = lock.lock())
        {
            if (state == OpCode.CLOSE)
                throw new ProtocolException(OpCode.name(opcode) + " after CLOSE");

            switch (opcode)
            {
                case OpCode.UNDEFINED:
                    throw new ProtocolException("UNDEFINED OpCode: " + OpCode.name(opcode));

                case OpCode.CONTINUATION:
                    if (state == OpCode.UNDEFINED)
                        throw new ProtocolException("CONTINUATION after fin==true");
                    if (fin)
                        state = OpCode.UNDEFINED;
                    break;

                case OpCode.CLOSE:
                    state = OpCode.CLOSE;
                    break;

                case OpCode.PING:
                case OpCode.PONG:
                    break;

                case OpCode.TEXT:
                case OpCode.BINARY:
                default:
                    if (state != OpCode.UNDEFINED)
                        throw new ProtocolException("DataFrame before fin==true");
                    if (!fin)
                        state = opcode;
                    break;
            }
        }
    }
}
