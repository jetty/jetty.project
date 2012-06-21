package org.eclipse.jetty.websocket.api;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;

public enum OpCode
{
    /**
     * OpCode for a {@link ContinuationFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    CONTINUATION((byte)0x00),

    /**
     * OpCode for a {@link TextFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    TEXT((byte)0x01),

    /**
     * OpCode for a {@link BinaryFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    BINARY((byte)0x02),

    /**
     * OpCode for a {@link CloseFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    CLOSE((byte)0x08),

    /**
     * OpCode for a {@link PingFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    PING((byte)0x09),

    /**
     * OpCode for a {@link PongFrame}
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

    public boolean isControlFrame() {
        return (opcode >= CLOSE.opcode);
    }
}
