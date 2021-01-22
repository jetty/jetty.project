//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NullSessionCacheFactory
 *
 * Factory for NullSessionCaches.
 */
public class NullSessionCacheFactory extends AbstractSessionCacheFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(NullSessionCacheFactory.class);
    
    @Override
    public int getEvictionPolicy()
    {
        return SessionCache.EVICT_ON_SESSION_EXIT; //never actually stored
    }

    @Override
    public void setEvictionPolicy(int evictionPolicy)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Ignoring eviction policy setting for NullSessionCaches");
    }

    @Override
    public boolean isSaveOnInactiveEvict()
    {
        return false; //never kept in cache
    }

    @Override
    public void setSaveOnInactiveEvict(boolean saveOnInactiveEvict)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Ignoring eviction policy setting for NullSessionCaches");
    }
    
    @Override
    public boolean isInvalidateOnShutdown()
    {
        return false; //meaningless for NullSessionCache
    }

    @Override
    public void setInvalidateOnShutdown(boolean invalidateOnShutdown)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Ignoring invalidateOnShutdown setting for NullSessionCaches");
    }

    @Override
    public SessionCache newSessionCache(SessionHandler handler)
    {
        return new NullSessionCache(handler);
    }
}
