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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TestSessionDataStore
 *
 * Make a fake session data store (non clustered!) that creates a new SessionData object
 * every time load(id) is called.
 */
public class TestableSessionDataStore extends AbstractSessionDataStore
{
    public Map<String, SessionData> _map = new ConcurrentHashMap<>();
    public AtomicInteger _numSaves = new AtomicInteger(0);

    public final boolean _passivating;

    public TestableSessionDataStore()
    {
        _passivating = false;
    }

    public TestableSessionDataStore(boolean passivating)
    {
        _passivating = passivating;
    }

    @Override
    public void initialize(SessionContext context) throws Exception
    {
        super.initialize(context);
    }
    
    @Override
    public boolean isPassivating()
    {
        return _passivating;
    }

    @Override
    public boolean doExists(String id) throws Exception
    {
        return _map.containsKey(id);
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {
        SessionData sd = _map.get(id);
        if (sd == null)
            return null;
        SessionData nsd = new SessionData(id, "", "", System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis(), 0);
        nsd.copy(sd);
        return nsd;
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        return (_map.remove(id) != null);
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        _map.put(id, data);
        _numSaves.addAndGet(1);
    }

    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {
        HashSet<String> set = new HashSet<>();
        long now = System.currentTimeMillis();

        for (SessionData d : _map.values())
        {
            if (d.getExpiry() > 0 && d.getExpiry() <= now)
                set.add(d.getId());
        }
        return set;
    }

    @Override
    public Set<String> doGetExpired(long timeLimit)
    {
        Set<String> set =  new HashSet<>();
        
        for (SessionData d:_map.values())
        {
            if (d.getExpiry() > 0 && d.getExpiry() <= timeLimit)
                set.add(d.getId());
        }
        return set;
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        //noop
    }
}
