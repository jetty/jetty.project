package org.eclipse.jetty.quic.common;

import java.util.HashMap;
import java.util.Map;

public enum StreamType
{
    CLIENT_BIDIRECTIONAL(0x00),
    SERVER_BIDIRECTIONAL(0x01),
    CLIENT_UNIDIRECTIONAL(0x02),
    SERVER_UNIDIRECTIONAL(0x03);

    public static StreamType from(long streamId)
    {
        int type = ((int)(streamId)) & 0b11;
        return Types.types.get(type);
    }

    public static boolean isUnidirectional(long streamId)
    {
        return (streamId & 0b01) == 0b01;
    }

    public static boolean isBidirectional(long streamId)
    {
        return (streamId & 0b01) == 0b00;
    }

    private final int type;

    private StreamType(int type)
    {
        this.type = type;
        Types.types.put(type, this);
    }

    public int type()
    {
        return type;
    }

    private static class Types
    {
        private static final Map<Integer, StreamType> types = new HashMap<>();
    }
}
