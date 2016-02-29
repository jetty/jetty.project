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

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * ExpiryInspector
 *
 *
 */
public class ExpiryInspector implements SessionInspector
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    protected Set<String> _expiryCandidates;
    protected SessionIdManager _idManager;
    protected AbstractSessionStore _sessionStore;
    
    
    
    /**
     * @param sessionStore
     * @param idManager
     */
    public ExpiryInspector (AbstractSessionStore sessionStore, SessionIdManager idManager)
    {
        _idManager = idManager;
        _sessionStore = sessionStore;
    }
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#inspect(org.eclipse.jetty.server.session.SessionStore, org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void inspect(Session s)
    {
        //Does the session object think it is expired?
        long now = System.currentTimeMillis();
        if (s.isExpiredAt(now))
            _expiryCandidates.add(s.getId());
    }

    
    
    
    /**
     * @return the expiryCandidates
     */
    public Set<String> getExpiryCandidates()
    {
        return _expiryCandidates;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#preInspection()
     */
    @Override
    public void preInspection()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("PreInspection");
        _expiryCandidates = new HashSet<String>();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionInspector#postInspection()
     */
    @Override
    public void postInspection()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("ExpiryInspector checking expiration for {}", _expiryCandidates);

        try
        {
            Set<String> candidates = _sessionStore.checkExpiration(_expiryCandidates);
            for (String id:candidates)
            {  
                _idManager.expireAll(id);
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        finally
        {
            _expiryCandidates = null;
        }
    }
}
