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

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * IdleExpiryInspector
 *
 * Checks if a session is idle
 */
public class IdleInspector implements SessionInspector
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    protected Set<String> _idleCandidates;
    protected AbstractSessionStore _sessionStore;
    
    /**
     * @param sessionStore
     */
    public IdleInspector (AbstractSessionStore sessionStore)
    {
        _sessionStore = sessionStore;
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#inspect(org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void inspect(Session s)
    {
        //Does the session object think it is expired?
        long now = System.currentTimeMillis();
        if (s.isExpiredAt(now))
            return;
        
        if (s.isValid() && s.isIdleLongerThan(_sessionStore.getIdlePassivationTimeoutSec()))
        {
            _idleCandidates.add(s.getId());
        };
    }

  
    /**
     * @return the idleCandidates
     */
    public Set<String> getIdleCandidates()
    {
        return _idleCandidates;
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#preInspection()
     */
    @Override
    public void preInspection()
    {
        _idleCandidates = new HashSet<String>();
    }
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#postInspection()
     */
    @Override
    public void postInspection()
    {
        for (String id:_idleCandidates)
        {
            try
            {
                _sessionStore.passivateIdleSession(id);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }

        _idleCandidates = null;
    }
}
