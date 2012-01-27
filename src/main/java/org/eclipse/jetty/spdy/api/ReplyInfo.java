package org.eclipse.jetty.spdy.api;

public class ReplyInfo
{
    public static final byte FLAG_FIN = 1;

    private final Headers headers;
    private final boolean close;

    public ReplyInfo(boolean close)
    {
        this(new Headers(), close);
    }

    public ReplyInfo(Headers headers, boolean close)
    {
        this.headers = headers;
        this.close = close;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return close;
    }

    public byte getFlags()
    {
        return isClose() ? FLAG_FIN : 0;
    }
}
