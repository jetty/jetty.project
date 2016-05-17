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


package org.eclipse.jetty.nosql;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionData;


/**
 * NoSqlSessionDataStore
 *
 *
 */
public abstract class NoSqlSessionDataStore extends AbstractSessionDataStore
{
    
    public class NoSqlSessionData extends SessionData
    {
        private Object _version;
        private Set<String> _dirtyAttributes = new HashSet<String>();
        

        public NoSqlSessionData(String id, String cpath, String vhost, long created, long accessed, long lastAccessed, long maxInactiveMs)
        {
            super(id, cpath, vhost, created, accessed, lastAccessed, maxInactiveMs);
        }
        
        public void setVersion (Object v)
        {
            _version = v;
        }
        
        public Object getVersion ()
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
        
        public Set<String> getAllAttributeNames ()
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
