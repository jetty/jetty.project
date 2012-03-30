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

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + associatedStreamId;
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        PushSynInfo other = (PushSynInfo)obj;
        if (associatedStreamId != other.associatedStreamId)
            return false;
        return true;
    }
}
