package org.eclipse.jetty.spdy.api;

public class SynInfo extends AbstractSynInfo
{

    public SynInfo(boolean close)
    {
        super(close);
    }

    public SynInfo(Headers headers, boolean close)
    {
        super(headers,close);
    }

    public SynInfo(Headers headers, boolean close, byte priority)
    {
        super(headers,close,priority);
    }

    @Override
    public boolean isUnidirectional()
    {
        return false;
    }

    @Override
    public int getAssociatedStreamId()
    {
        return 0;
    }

}
