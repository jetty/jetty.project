package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.SynInfo;

/* ------------------------------------------------------------ */
/**
 * <p>A subclass container of {@link SynInfo} for unidirectional streams</p>
 */
public class PushSynInfo extends SynInfo
{
    public static final byte FLAG_UNIDIRECTIONAL = 2;
    
    private int associatedStreamId;
    
    public PushSynInfo(int associatedStreamId, SynInfo synInfo){
        super(synInfo.getHeaders(), synInfo.isClose(), synInfo.getPriority());
        this.associatedStreamId = associatedStreamId;
    }
    
    /**
     * @return the close and unidirectional flags as integer
     * @see #FLAG_CLOSE
     */
    @Override
    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_CLOSE : 0;
        flags += FLAG_UNIDIRECTIONAL;
        return flags;
    }
    
    /**
     * @return the id of the associated stream
     */
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
