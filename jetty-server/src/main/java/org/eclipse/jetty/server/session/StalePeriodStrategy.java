//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
 *
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
            return (session.getSessionData().getAccessed() - session.getSessionData().getLastSaved() >= _staleMs);
        }
            
    }
    
    
    public long getStaleSec ()
    {
        return (_staleMs<=0?0L:_staleMs/1000L);
    }
    
    public void setStaleSec (long sec)
    {
        if (sec == 0)
            _staleMs = 0L;
        else
            _staleMs = sec * 1000L;
    }

}
