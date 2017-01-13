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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * TestSessionDataStore
 *
 * Make a fake session data store that creates a new SessionData object
 * every time load(id) is called.
 */
public class TestSessionDataStore extends AbstractSessionDataStore
{
    public Map<String,SessionData> _map = new HashMap<>();


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        return false;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        return _map.containsKey(id);
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMap#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        SessionData sd = _map.get(id);
        if (sd == null)
            return null;
        SessionData nsd = new SessionData(id,"","",System.currentTimeMillis(),System.currentTimeMillis(), System.currentTimeMillis(),0 );
        nsd.copy(sd);
        return nsd;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMap#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        return (_map.remove(id) != null);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        _map.put(id,  data);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doGetExpired(java.util.Set)
     */
    @Override
    public Set<String> doGetExpired(Set<String> candidates)
    {
        return Collections.emptySet();
    }

}