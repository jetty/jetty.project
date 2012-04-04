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
     * @see #FLAG_UNIDIRECTIONAL
     */
    @Override
    public byte getFlags()
    {
        byte flags = super.getFlags();
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

}
