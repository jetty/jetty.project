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

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does not actually cache any Session objects. Useful for testing.
 * Also useful if you do not want to share Session objects with the same id between
 * simultaneous requests: note that this means that context forwarding can't share
 * the same id either.
 */
public class NullSessionCache extends AbstractSessionCache
{
    private static final Logger LOG = LoggerFactory.getLogger(NullSessionCache.class);

    public NullSessionCache(SessionManager manager)
    {
        super(manager);
        super.setEvictionPolicy(EVICT_ON_SESSION_EXIT);
    }

    @Override
    public void shutdown()
    {
    }

    @Override
    public Session newSession(SessionData data)
    {
        return new Session(getSessionManager(), data);
    }

    @Override
    public Session doGet(String id)
    {
        //do not cache anything
        return null;
    }

    @Override
    public Session doPutIfAbsent(String id, Session session)
    {
        //nothing was stored previously
        return null;
    }

    @Override
    public boolean doReplace(String id, Session oldValue, Session newValue)
    {
        //always accept new value
        return true;
    }

    @Override
    public Session doDelete(String id)
    {
        return null;
    }

    @Override
    public void setEvictionPolicy(int evictionTimeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Ignoring eviction setting: {}", evictionTimeout);
    }

    @Override
    protected Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction)
    {
        return mappingFunction.apply(id);
    }
}
