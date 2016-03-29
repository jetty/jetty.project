//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session;

/**
 * StalePeriodStrategy
 *
 * A session is regarded as being stale if it has been 
 * x seconds since it was last read from the cluster.
 */
public class StalePeriodStrategy implements StalenessStrategy
{
    protected long _staleMs = 0;

    /** 
     * @see org.eclipse.jetty.server.session.StalenessStrategy#isStale(org.eclipse.jetty.server.session.Session)
     */
    @Override
    public boolean isStale (Session session)
    {
        if (session == null)
            return false;
        
        //never persisted, must be fresh session
        if (session.getSessionData().getLastSaved() == 0)
            return false;
        
        if (_staleMs <= 0)
        {
            //TODO always stale, never stale??
            return false;
        }
        else
        {
           // return (session.getSessionData().getAccessed() - session.getSessionData().getLastSaved() >= _staleMs);
            return (System.currentTimeMillis() - session.getSessionData().getLastSaved() >= _staleMs);
        }
            
    }
    
    public long getStaleSec ()
    {
        return (_staleMs<=0?0L:_staleMs/1000L);
    }
    
    /**
     * The amount of time in seconds that a session can be held
     * in memory without being refreshed from the cluster.
     * @param sec the time in seconds
     */
    public void setStaleSec (long sec)
    {
        if (sec == 0)
            _staleMs = 0L;
        else
            _staleMs = sec * 1000L;
    }

}
