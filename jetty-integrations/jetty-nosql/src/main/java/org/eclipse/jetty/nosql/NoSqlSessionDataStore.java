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

package org.eclipse.jetty.nosql;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.session.AbstractSessionDataStore;
import org.eclipse.jetty.session.SessionData;

/**
 * NoSqlSessionDataStore
 */
public abstract class NoSqlSessionDataStore extends AbstractSessionDataStore
{

    public class NoSqlSessionData extends SessionData
    {
        private Object _version;
        private Set<String> _dirtyAttributes = new HashSet<>();

        public NoSqlSessionData(String id, String cpath, String vhost, long created, long accessed, long lastAccessed, long maxInactiveMs)
        {
            super(id, cpath, vhost, created, accessed, lastAccessed, maxInactiveMs);
            setVersion(0L);
        }

        public void setVersion(Object v)
        {
            _version = v;
        }

        public Object getVersion()
        {
            return _version;
        }

        @Override
        public void setDirty(String name)
        {
            super.setDirty(name);
            _dirtyAttributes.add(name);
        }

        public Set<String> takeDirtyAttributes()
        {
            Set<String> copy = new HashSet<>(_dirtyAttributes);
            _dirtyAttributes.clear();
            return copy;
        }

        public Set<String> getAllAttributeNames()
        {
            return new HashSet<String>(_attributes.keySet());
        }
    }

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new NoSqlSessionData(id, _context.getCanonicalContextPath(), _context.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }
}
