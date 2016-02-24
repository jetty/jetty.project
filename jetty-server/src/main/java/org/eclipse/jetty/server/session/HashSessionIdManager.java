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

import java.util.Set;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ConcurrentHashSet;

/**
 * HashSessionIdManager
 *
 *
 */
public class HashSessionIdManager extends AbstractSessionIdManager
{
    /**
     * @param server
     */
    public HashSessionIdManager(Server server)
    {
        super(server);
    }

    private final Set<String> _ids = new ConcurrentHashSet<String>();
    
    
    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#isIdInUse(java.lang.String)
     */
    @Override
    public boolean isIdInUse(String id)
    {
         return _ids.contains(id);
    }


    
    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#useId(org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void useId(Session session)
    {
        if (session == null)
            return;
        
       _ids.add(session.getId());
    }

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#removeId(java.lang.String)
     */
    @Override
    public boolean removeId(String id)
    {
       return _ids.remove(id);
    }

}
