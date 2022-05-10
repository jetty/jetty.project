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

import java.util.Collections;
import java.util.Set;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * NullSessionDataStore
 *
 * Does not actually store anything, useful for testing.
 */
@ManagedObject
public class NullSessionDataStore extends AbstractSessionDataStore
{
    @Override
    public SessionData doLoad(String id) throws Exception
    {
        return null;
    }

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new SessionData(id, _context.getCanonicalContextPath(), _context.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        return true;
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        //noop
    }

    @Override
    public Set<String> doCheckExpired(Set<String> candidates, long time)
    {
        return candidates; //whatever is suggested we accept
    }

    @Override
    public Set<String> doGetExpired(long timeLimit)
    {
        return Collections.emptySet();
    }
    
    /** 
     * @see org.eclipse.jetty.session.SessionDataStore#isPassivating()
     */
    @ManagedAttribute(value = "does this store serialize sessions", readonly = true)
    @Override
    public boolean isPassivating()
    {
        return false;
    }

    @Override
    public boolean doExists(String id)
    {
        return false;
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        //noop
    }
}
