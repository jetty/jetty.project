package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.api.WebSocket;

/**
 * A Base Frame as seen in <a href="https://tools.ietf.org/html/rfc6455#section-5.2">RFC 6455. Sec 5.2</a>
 * 
 * <pre>
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *   +-+-+-+-+-------+-+-------------+-------------------------------+
 *   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *   | |1|2|3|       |K|             |                               |
 *   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *   |     Extended payload length continued, if payload len == 127  |
 *   + - - - - - - - - - - - - - - - +-------------------------------+
 *   |                               |Masking-key, if MASK set to 1  |
 *   +-------------------------------+-------------------------------+
 *   | Masking-key (continued)       |          Payload Data         |
 *   +-------------------------------- - - - - - - - - - - - - - - - +
 *   :                     Payload Data continued ...                :
 *   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *   |                     Payload Data continued ...                |
 *   +---------------------------------------------------------------+
 * </pre>
 */
public class BaseFrame
{
    public final static byte OP_CONTINUATION = 0x00;
    public final static byte OP_TEXT = 0x01;
    public final static byte OP_BINARY = 0x02;
    public final static byte OP_EXT_DATA = 0x03;

    public final static byte OP_CONTROL = 0x08;
    public final static byte OP_CLOSE = 0x08;
    public final static byte OP_PING = 0x09;
    public final static byte OP_PONG = 0x0A;
    public final static byte OP_EXT_CTRL = 0x0B;
    
    private boolean fin;
    private boolean rsv1;
    private boolean rsv2;
    private boolean rsv3;
    private byte opcode = -1;
    private boolean mask = false;
    private long payloadLength;
    private byte maskingKey[];
    
    public final static int FLAG_FIN=0x8;

    public static boolean isLastFrame(byte flags)
    {
        return (flags & FLAG_FIN) != 0;
    }

    public static boolean isControlFrame(byte opcode)
    {
        return (opcode & OP_CONTROL) != 0;
    }

}
