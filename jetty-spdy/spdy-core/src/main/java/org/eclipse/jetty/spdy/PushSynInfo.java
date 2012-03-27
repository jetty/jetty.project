package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.AbstractSynInfo;

public class PushSynInfo extends AbstractSynInfo
{
    private int associatedStreamId;
    
    //TODO: remove constructors and add PSI(id,synInfo)
    public PushSynInfo(int associatedStreamId, boolean close){
        super(close);
        this.associatedStreamId = associatedStreamId;
    }
    
    public PushSynInfo(int associatedStreamId, Headers headers, boolean close){
        super(headers,close);
        this.associatedStreamId = associatedStreamId;
    }
    
    public PushSynInfo(int associatedStreamId, Headers headers, boolean close, byte priority)
    {
        super(headers,close,priority);
        this.associatedStreamId = associatedStreamId;
    }

    @Override
    public boolean isUnidirectional()
    {
        return true;
    }

    @Override
    public int getAssociatedStreamId()
    {
        return associatedStreamId;
    }
}
