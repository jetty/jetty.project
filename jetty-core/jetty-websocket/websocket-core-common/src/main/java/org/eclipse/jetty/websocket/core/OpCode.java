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

public final class OpCode
{
    /**
     * OpCode for a Continuation Frame
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public static final byte CONTINUATION = (byte)0x00;

    /**
     * OpCode for a Text Frame
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public static final byte TEXT = (byte)0x01;

    /**
     * OpCode for a Binary Frame
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public static final byte BINARY = (byte)0x02;

    /**
     * OpCode for a Close Frame
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public static final byte CLOSE = (byte)0x08;

    /**
     * OpCode for a Ping Frame
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public static final byte PING = (byte)0x09;

    /**
     * OpCode for a Pong Frame
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public static final byte PONG = (byte)0x0A;

    /**
     * An undefined OpCode
     */
    public static final byte UNDEFINED = (byte)-1;

    public static byte getOpCode(byte firstByte)
    {
        return (byte)(firstByte & 0x0F);
    }

    public static boolean isControlFrame(byte opcode)
    {
        switch (opcode)
        {
            case CLOSE:
            case PING:
            case PONG:
                return true;
            default:
                return false;
        }
    }

    public static boolean isDataFrame(byte opcode)
    {
        switch (opcode)
        {
            case TEXT:
            case BINARY:
            case CONTINUATION:
                return true;
            default:
                return false;
        }
    }

    /**
     * Test for known opcodes (per the RFC spec)
     *
     * @param opcode the opcode to test
     * @return true if known. false if unknown, undefined, or reserved
     */
    public static boolean isKnown(byte opcode)
    {
        switch (opcode)
        {
            case CLOSE:
            case PING:
            case PONG:
            case TEXT:
            case BINARY:
            case CONTINUATION:
                return true;
            default:
                return false;
        }
    }

    public static String name(byte opcode)
    {
        switch (opcode)
        {
            case -1:
                return "NO-OP";
            case CONTINUATION:
                return "CONTINUATION";
            case TEXT:
                return "TEXT";
            case BINARY:
                return "BINARY";
            case CLOSE:
                return "CLOSE";
            case PING:
                return "PING";
            case PONG:
                return "PONG";
            default:
                return "NON-SPEC[" + opcode + "]";
        }
    }
}
