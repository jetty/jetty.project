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
 * NullSessionCacheFactory
 *
 * Factory for NullSessionCaches.
 */
public class NullSessionCacheFactory implements SessionCacheFactory
{    
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
     * @return the removeUnloadableSessions
     */
    public boolean isRemoveUnloadableSessions()
    {
        return _removeUnloadableSessions;
    }



    /**
     * @param removeUnloadableSessions the removeUnloadableSessions to set
     */
    public void setRemoveUnloadableSessions(boolean removeUnloadableSessions)
    {
        _removeUnloadableSessions = removeUnloadableSessions;
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionCacheFactory#getSessionCache(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionCache getSessionCache(SessionHandler handler)
    {
        NullSessionCache cache = new NullSessionCache(handler);
        cache.setSaveOnCreate(isSaveOnCreate());
        cache.setRemoveUnloadableSessions(isRemoveUnloadableSessions());
        return cache;
        
    }

}
