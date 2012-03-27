package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.AbstractSynInfo;
import org.eclipse.jetty.spdy.api.SynInfo;

public class PushSynInfo extends AbstractSynInfo
{
    private int associatedStreamId;
    
    public PushSynInfo(int associatedStreamId, SynInfo synInfo){
        super(synInfo.getHeaders(), synInfo.isClose(), synInfo.getPriority());
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
