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
 * DefaultSessionCacheFactory
 *
 * Factory for creating new DefaultSessionCaches.
 */
public class DefaultSessionCacheFactory implements SessionCacheFactory
{
    int _evictionPolicy;
    boolean _saveOnInactiveEvict;
    boolean _removeUnloadableSessions;

    
    
    
    public boolean isRemoveUnloadableSessions()
    {
        return _removeUnloadableSessions;
    }




    public void setRemoveUnloadableSessions(boolean removeUnloadableSessions)
    {
        _removeUnloadableSessions = removeUnloadableSessions;
    }




    public int getEvictionPolicy()
    {
        return _evictionPolicy;
    }




    public void setEvictionPolicy(int evictionPolicy)
    {
        _evictionPolicy = evictionPolicy;
    }




    public boolean isSaveOnInactiveEvict()
    {
        return _saveOnInactiveEvict;
    }




    public void setSaveOnInactiveEvict(boolean saveOnInactiveEvict)
    {
        _saveOnInactiveEvict = saveOnInactiveEvict;
    }


    

    /** 
     * @see org.eclipse.jetty.server.session.SessionCacheFactory#getSessionCache(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionCache getSessionCache (SessionHandler handler)
    {
        DefaultSessionCache cache = new DefaultSessionCache(handler);
        cache.setEvictionPolicy(getEvictionPolicy());
        cache.setSaveOnInactiveEviction(isSaveOnInactiveEvict());
        cache.setRemoveUnloadableSessions(isRemoveUnloadableSessions());
        return cache;
    }

    
}
