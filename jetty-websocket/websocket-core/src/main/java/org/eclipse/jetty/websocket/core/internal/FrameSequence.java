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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;

public class FrameSequence
{
    // TODO should we be able to get a non fin frame then get a close frame without error
    private byte state= OpCode.UNDEFINED;

    public void check(byte opcode, boolean fin) throws ProtocolException
    {
        synchronized (this)
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
                    return;

                case OpCode.CLOSE:
                    state = OpCode.CLOSE;
                    return;

                case OpCode.PING:
                case OpCode.PONG:
                    return;

                case OpCode.TEXT:
                case OpCode.BINARY:
                default:
                    if (state != OpCode.UNDEFINED)
                        throw new ProtocolException("DataFrame before fin==true");
                    if (!fin)
                        state = opcode;
                    return;
            }
        }
    }
}
