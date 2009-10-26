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
public class HashSessionIdManager extends AbstractLifeCycle implements SessionIdManager
{
    private final static String __NEW_SESSION_ID="org.eclipse.jetty.server.newSessionId";  
    protected final static String SESSION_ID_RANDOM_ALGORITHM = "SHA1PRNG";
    protected final static String SESSION_ID_RANDOM_ALGORITHM_ALT = "IBMSecureRandom";

    MultiMap<String> _sessions;
    protected Random _random;
    private boolean _weakRandom;
    private String _workerName;

    /* ------------------------------------------------------------ */
    public HashSessionIdManager()
    {
    }

    /* ------------------------------------------------------------ */
    public HashSessionIdManager(Random random)
    {
        _random=random;
      
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the workname. If set, the workername is dot appended to the session
     * ID and can be used to assist session affinity in a load balancer.
     * 
     * @return String or null
     */
    public String getWorkerName()
    {
        return _workerName;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the workname. If set, the workername is dot appended to the session
     * ID and can be used to assist session affinity in a load balancer.
     * 
     * @param workerName
     */
    public void setWorkerName(String workerName)
    {
        _workerName=workerName;
    }

    /* ------------------------------------------------------------ */
    /** Get the session ID with any worker ID.
     * 
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
     * @param request
     * @return sessionId without any worker ID.
     */
    public String getClusterId(String nodeId) 
    {
        int dot=nodeId.lastIndexOf('.');
        return (dot>0)?nodeId.substring(0,dot):nodeId;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart()
    {
        if (_random==null)
        {      
            try 
            {
                //This operation may block on some systems with low entropy. See this page
                //for workaround suggestions:
                //http://docs.codehaus.org/display/JETTY/Connectors+slow+to+startup
                Log.debug("Init SecureRandom."); 
                _random=SecureRandom.getInstance(SESSION_ID_RANDOM_ALGORITHM);
            }
            catch (NoSuchAlgorithmException e)
            {
                try
                {
                    _random=SecureRandom.getInstance(SESSION_ID_RANDOM_ALGORITHM_ALT);
                    _weakRandom=false;
                }
                catch (NoSuchAlgorithmException e_alt)
                {
                    Log.warn("Could not generate SecureRandom for session-id randomness",e);
                    _random=new Random();
                    _weakRandom=true;
                }
            }
        }
        _random.setSeed(_random.nextLong()^System.currentTimeMillis()^hashCode()^Runtime.getRuntime().freeMemory());
        _sessions=new MultiMap<String>(true);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop()
    {
        if (_sessions!=null)
            _sessions.clear(); // Maybe invalidate?
        _sessions=null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.SessionManager.MetaManager#idInUse(java.lang.String)
     */
    public boolean idInUse(String id)
    {
        return _sessions.containsKey(id);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.SessionManager.MetaManager#addSession(javax.servlet.http.HttpSession)
     */
    public void addSession(HttpSession session)
    {
        _sessions.add(getClusterId(session.getId()),session);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.SessionManager.MetaManager#addSession(javax.servlet.http.HttpSession)
     */
    public void removeSession(HttpSession session)
    {
        _sessions.removeValue(getClusterId(session.getId()),session);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.SessionManager.MetaManager#invalidateAll(java.lang.String)
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

    /* ------------------------------------------------------------ */
    /*
     * new Session ID. If the request has a requestedSessionID which is unique,
     * that is used. The session ID is created as a unique random long XORed with
     * connection specific information, base 36.
     * @param request 
     * @param created 
     * @return Session ID.
     */
    public String newSessionId(HttpServletRequest request, long created)
    {
        synchronized (this)
        {
            // A requested session ID can only be used if it is in use already.
            String requested_id=request.getRequestedSessionId();

            if (requested_id!=null)
            {
                String cluster_id=getClusterId(requested_id);
                if (idInUse(cluster_id))
                    return cluster_id;
            }

            // Else reuse any new session ID already defined for this request.
            String new_id=(String)request.getAttribute(__NEW_SESSION_ID);
            if (new_id!=null&&idInUse(new_id))
                return new_id;

            // pick a new unique ID!
            String id=null;
            while (id==null||id.length()==0||idInUse(id))
            {
                long r=_weakRandom
                ?(hashCode()^Runtime.getRuntime().freeMemory()^_random.nextInt()^(((long)request.hashCode())<<32))
                :_random.nextLong();
                r^=created;
                if (request.getRemoteAddr()!=null)
                    r^=request.getRemoteAddr().hashCode();
                if (r<0)
                    r=-r;
                id=Long.toString(r,36);
            }

            request.setAttribute(__NEW_SESSION_ID,id);
            return id;
        }
    }

    /* ------------------------------------------------------------ */
    public Random getRandom()
    {
        return _random;
    }

    /* ------------------------------------------------------------ */
    public void setRandom(Random random)
    {
        _random=random;
        _weakRandom=false;
    }

}
