//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
 * AbstractSessionCacheFactory
 * 
 * Base class for SessionCacheFactories.
 *
 */
public abstract class AbstractSessionCacheFactory implements SessionCacheFactory
{
    int _evictionPolicy;
    boolean _saveOnInactiveEvict;
    boolean _saveOnCreate;
    boolean _removeUnloadableSessions;
    boolean _flushOnResponseCommit;
    boolean _invalidateOnShutdown;
    
    public abstract SessionCache newSessionCache(SessionHandler handler);

    public boolean isInvalidateOnShutdown()
    {
        return _invalidateOnShutdown;
    }

    public void setInvalidateOnShutdown(boolean invalidateOnShutdown)
    {
        _invalidateOnShutdown = invalidateOnShutdown;
    }

    /**
     * @return the flushOnResponseCommit
     */
    public boolean isFlushOnResponseCommit()
    {
        return _flushOnResponseCommit;
    }

    /**
     * @param flushOnResponseCommit the flushOnResponseCommit to set
     */
    public void setFlushOnResponseCommit(boolean flushOnResponseCommit)
    {
        _flushOnResponseCommit = flushOnResponseCommit;
    }

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
     * @return the evictionPolicy
     */
    public int getEvictionPolicy()
    {
        return _evictionPolicy;
    }

    /**
     * @param evictionPolicy the evictionPolicy to set
     */
    public void setEvictionPolicy(int evictionPolicy)
    {
        _evictionPolicy = evictionPolicy;
    }

    /**
     * @return the saveOnInactiveEvict
     */
    public boolean isSaveOnInactiveEvict()
    {
        return _saveOnInactiveEvict;
    }

    /**
     * @param saveOnInactiveEvict the saveOnInactiveEvict to set
     */
    public void setSaveOnInactiveEvict(boolean saveOnInactiveEvict)
    {
        _saveOnInactiveEvict = saveOnInactiveEvict;
    }

    @Override
    public SessionCache getSessionCache(SessionHandler handler)
    {
        SessionCache cache = newSessionCache(handler);
        cache.setEvictionPolicy(getEvictionPolicy());
        cache.setSaveOnInactiveEviction(isSaveOnInactiveEvict());
        cache.setSaveOnCreate(isSaveOnCreate());
        cache.setRemoveUnloadableSessions(isRemoveUnloadableSessions());
        cache.setFlushOnResponseCommit(isFlushOnResponseCommit());
        cache.setInvalidateOnShutdown(isInvalidateOnShutdown());
        return cache;
    }
}
