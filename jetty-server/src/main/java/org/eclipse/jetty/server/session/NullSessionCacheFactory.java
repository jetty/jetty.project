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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * NullSessionCacheFactory
 *
 * Factory for NullSessionCaches.
 */
public class NullSessionCacheFactory extends AbstractSessionCacheFactory
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
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
