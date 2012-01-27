package org.eclipse.jetty.spdy.api;

public class HeadersInfo
{
    public static final byte FLAG_FIN = 1;
    public static final byte FLAG_RESET_COMPRESSION = 2;

    private final boolean close;
    private final boolean resetCompression;
    private final Headers headers;

    public HeadersInfo(Headers headers, boolean close)
    {
        this(headers, close, false);
    }

    public HeadersInfo(Headers headers, boolean close, boolean resetCompression)
    {
        this.headers = headers;
        this.close = close;
        this.resetCompression = resetCompression;
    }

    public boolean isClose()
    {
        return close;
    }

    public boolean isResetCompression()
    {
        return resetCompression;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_FIN : 0;
        flags += isResetCompression() ? FLAG_RESET_COMPRESSION : 0;
        return flags;
    }
}
