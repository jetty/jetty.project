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
    boolean _saveOnCreate;
    boolean _removeUnloadableSessions;

    
    /**
     * @return the saveOnCreate
     */
    public boolean isSaveOnCreate()
    {
        return _saveOnCreate;
    }



    /**
     * @param saveOnCreate the saveOnCreate to set
     */
    public void setSaveOnCreate(boolean saveOnCreate)
    {
        _saveOnCreate = saveOnCreate;
    }



    
    
    
    /**
     * @return
     */
    public boolean isRemoveUnloadableSessions()
    {
        return _removeUnloadableSessions;
    }




    /**
     * @param removeUnloadableSessions
     */
    public void setRemoveUnloadableSessions(boolean removeUnloadableSessions)
    {
        _removeUnloadableSessions = removeUnloadableSessions;
    }




    /**
     * @return
     */
    public int getEvictionPolicy()
    {
        return _evictionPolicy;
    }




    /**
     * @param evictionPolicy
     */
    public void setEvictionPolicy(int evictionPolicy)
    {
        _evictionPolicy = evictionPolicy;
    }




    /**
     * @return
     */
    public boolean isSaveOnInactiveEvict()
    {
        return _saveOnInactiveEvict;
    }




    /**
     * @param saveOnInactiveEvict
     */
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
        cache.setSaveOnCreate(isSaveOnCreate());
        cache.setRemoveUnloadableSessions(isRemoveUnloadableSessions());
        return cache;
    }

    
}
