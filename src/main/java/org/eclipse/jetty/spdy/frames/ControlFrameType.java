package org.eclipse.jetty.spdy.frames;

import java.util.HashMap;
import java.util.Map;

public enum ControlFrameType
{
    SYN_STREAM((short)1),
    SYN_REPLY((short)2),
    RST_STREAM((short)3),
    SETTINGS((short)4),
    NOOP((short)5),
    PING((short)6),
    GO_AWAY((short)7),
    HEADERS((short)8),
    WINDOW_UPDATE((short)9);

    public static ControlFrameType from(short code)
    {
        return Mapper.codes.get(code);
    }

    private final short code;

    private ControlFrameType(short code)
    {
        this.code = code;
        Mapper.codes.put(code, this);
    }

    public short getCode()
    {
        return code;
    }

    private static class Mapper
    {
        private static final Map<Short, ControlFrameType> codes = new HashMap<>();
    }
}
