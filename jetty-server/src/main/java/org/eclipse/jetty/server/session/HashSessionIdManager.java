// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.session;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.session.AbstractSessionManager.Session;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/**
 * HashSessionIdManager. An in-memory implementation of the session ID manager.
 */
public class HashSessionIdManager extends AbstractSessionIdManager
{
    MultiMap<String> _sessions;

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
    /** Get the session ID with any worker ID.
     * 
     * @param clusterId
     * @param request
     * @return sessionId plus any worker ID.
     */
    public String getNodeId(String clusterId,HttpServletRequest request) 
    {
        String worker=request==null?null:(String)request.getAttribute("org.eclipse.http.ajp.JVMRoute");
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
        _sessions=new MultiMap<String>(true);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        if (_sessions!=null)
            _sessions.clear(); // Maybe invalidate?
        _sessions=null;
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#idInUse(String)
     */
    public boolean idInUse(String id)
    {
        return _sessions.containsKey(id);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#addSession(HttpSession)
     */
    public void addSession(HttpSession session)
    {
        _sessions.add(getClusterId(session.getId()),session);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#removeSession(HttpSession)
     */
    public void removeSession(HttpSession session)
    {
        _sessions.removeValue(getClusterId(session.getId()),session);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see SessionIdManager#invalidateAll(String)
     */
    public void invalidateAll(String id)
    {
	// Do not use interators as this method tends to be called recursively 
	// by the invalidate calls.
	while (_sessions.containsKey(id))
	{
	    Session session=(Session)_sessions.getValue(id,0);
	    if (session.isValid())
		session.invalidate();
	    else
		_sessions.removeValue(id,session);
	}
    }

}
