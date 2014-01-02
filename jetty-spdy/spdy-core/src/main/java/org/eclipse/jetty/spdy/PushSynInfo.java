//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.SynInfo;

/* ------------------------------------------------------------ */
/**
 * <p>A subclass container of {@link SynInfo} for unidirectional streams</p>
 */
public class PushSynInfo extends SynInfo
{
    public static final byte FLAG_UNIDIRECTIONAL = 2;
    
    private int associatedStreamId;
    
    public PushSynInfo(int associatedStreamId, PushInfo pushInfo){
        super(pushInfo.getTimeout(), pushInfo.getUnit(), pushInfo.getHeaders(), pushInfo.isClose(), (byte)0);
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
