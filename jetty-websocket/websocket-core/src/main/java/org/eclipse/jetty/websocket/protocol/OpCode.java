// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.protocol;

import java.util.HashMap;
import java.util.Map;

public enum OpCode
{
    /**
     * OpCode for a Continuation Frame
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    CONTINUATION((byte)0x00),

    /**
     * OpCode for a Text Frame
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    TEXT((byte)0x01),

    /**
     * OpCode for a Binary Frame
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    BINARY((byte)0x02),

    /**
     * OpCode for a Close Frame
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    CLOSE((byte)0x08),

    /**
     * OpCode for a Ping Frame
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    PING((byte)0x09),

    /**
     * OpCode for a Pong Frame
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    PONG((byte)0x0A);

    private static class Codes
    {
        private static final Map<Byte, OpCode> codes = new HashMap<>();
    }

    /**
     * Get OpCode from specified value.
     * 
     * @param opcode
     * @return
     */
    public static OpCode from(byte opcode)
    {
        return Codes.codes.get(opcode);
    }

    private byte opcode;

    private OpCode(byte opcode)
    {
        this.opcode = opcode;
        Codes.codes.put(opcode,this);
    }

    public byte getCode()
    {
        return this.opcode;
    }

    public boolean isControlFrame()
    {
        return (opcode >= CLOSE.opcode);
    }

    public boolean isDataFrame()
    {
        return (this == TEXT) || (this == BINARY);
    }
}
