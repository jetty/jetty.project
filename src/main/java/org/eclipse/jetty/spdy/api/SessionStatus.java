package org.eclipse.jetty.spdy.api;

import java.util.HashMap;
import java.util.Map;

public enum SessionStatus
{
    OK(0),
    PROTOCOL_ERROR(1);

    public static SessionStatus from(int code)
    {
        return Mapper.codes.get(code);
    }

    private final int code;

    private SessionStatus(int code)
    {
        this.code = code;
        Mapper.codes.put(code, this);
    }

    public int getCode()
    {
        return code;
    }

    private static class Mapper
    {
        private static final Map<Integer, SessionStatus> codes = new HashMap<>();
    }
}
