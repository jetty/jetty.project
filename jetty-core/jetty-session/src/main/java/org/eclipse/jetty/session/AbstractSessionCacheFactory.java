//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session;

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
    
    public abstract SessionCache newSessionCache(SessionManager manager);

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
    public boolean isSaveOnInactiveEviction()
    {
        return _saveOnInactiveEvict;
    }

    /**
     * @param saveOnInactiveEvict the saveOnInactiveEvict to set
     */
    public void setSaveOnInactiveEviction(boolean saveOnInactiveEvict)
    {
        _saveOnInactiveEvict = saveOnInactiveEvict;
    }

    @Override
    public SessionCache getSessionCache(SessionManager manager)
    {
        SessionCache cache = newSessionCache(manager);
        cache.setEvictionPolicy(getEvictionPolicy());
        cache.setSaveOnInactiveEviction(isSaveOnInactiveEviction());
        cache.setSaveOnCreate(isSaveOnCreate());
        cache.setRemoveUnloadableSessions(isRemoveUnloadableSessions());
        cache.setFlushOnResponseCommit(isFlushOnResponseCommit());
        cache.setInvalidateOnShutdown(isInvalidateOnShutdown());
        return cache;
    }
}
