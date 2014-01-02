//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.SessionIdManager;

/* ------------------------------------------------------------ */
/**
 * HashSessionIdManager. An in-memory implementation of the session ID manager.
 */
public class HashSessionIdManager extends AbstractSessionIdManager
{
    private final Map<String, Set<WeakReference<HttpSession>>> _sessions = new HashMap<String, Set<WeakReference<HttpSession>>>();

    /* ------------------------------------------------------------ */
    public HashSessionIdManager()
    {
    }

    /* ------------------------------------------------------------ */
    public HashSessionIdManager(Random random)
    {
        super(random);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Collection of String session IDs
     */
    public Collection<String> getSessions()
    {
        return Collections.unmodifiableCollection(_sessions.keySet());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Collection of Sessions for the passed session ID
     */
    public Collection<HttpSession> getSession(String id)
    {
        ArrayList<HttpSession> sessions = new ArrayList<HttpSession>();
        Set<WeakReference<HttpSession>> refs =_sessions.get(id);
        if (refs!=null)
        {
            for (WeakReference<HttpSession> ref: refs)
            {
                HttpSession session = ref.get();
                if (session!=null)
                    sessions.add(session);
            }
        }
        return sessions;
    }
    /* ------------------------------------------------------------ */
    /** Get the session ID with any worker ID.
     * 
     * @param clusterId
     * @param request
     * @return sessionId plus any worker ID.
     */
    public String getNodeId(String clusterId,HttpServletRequest request) 
    {
        // used in Ajp13Parser
        String worker=request==null?null:(String)request.getAttribute("org.eclipse.jetty.ajp.JVMRoute");
        if (worker!=null) 
            return clusterId+'.'+worker; 
        
        if (_workerName!=null) 
            return clusterId+'.'+_workerName;
       
        return clusterId;
    }

    /* ------------------------------------------------------------ */
    /** Get the session ID without any worker ID.
     * 
     * @param nodeId the node id
     * @return sessionId without any worker ID.
     */
    public String getClusterId(String nodeId) 
    {
        int dot=nodeId.lastIndexOf('.');
        return (dot>0)?nodeId.substring(0,dot):nodeId;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {        
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        _sessions.clear(); 
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#idInUse(String)
     */
    public boolean idInUse(String id)
    {
        synchronized (this)
        {
            return _sessions.containsKey(id);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#addSession(HttpSession)
     */
    public void addSession(HttpSession session)
    {
        String id = getClusterId(session.getId());
        WeakReference<HttpSession> ref = new WeakReference<HttpSession>(session);
        
        synchronized (this)
        {
            Set<WeakReference<HttpSession>> sessions = _sessions.get(id);
            if (sessions==null)
            {
                sessions=new HashSet<WeakReference<HttpSession>>();
                _sessions.put(id,sessions);
            }
            sessions.add(ref);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#removeSession(HttpSession)
     */
    public void removeSession(HttpSession session)
    {
        String id = getClusterId(session.getId());
        
        synchronized (this)
        {
            Collection<WeakReference<HttpSession>> sessions = _sessions.get(id);
            if (sessions!=null)
            {
                for (Iterator<WeakReference<HttpSession>> iter = sessions.iterator(); iter.hasNext();)
                {
                    WeakReference<HttpSession> ref = iter.next();
                    HttpSession s=ref.get();
                    if (s==null)
                    {
                        iter.remove();
                        continue;
                    }
                    if (s==session)
                    {
                        iter.remove();
                        break;
                    }
                }
                if (sessions.isEmpty())
                    _sessions.remove(id);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#invalidateAll(String)
     */
    public void invalidateAll(String id)
    {
        Collection<WeakReference<HttpSession>> sessions;
        synchronized (this)
        {
            sessions = _sessions.remove(id);
        }
        
        if (sessions!=null)
        {
            for (WeakReference<HttpSession> ref: sessions)
            {
                AbstractSession session=(AbstractSession)ref.get();
                if (session!=null && session.isValid())
                    session.invalidate();
            }
            sessions.clear();
        }
    }

}
