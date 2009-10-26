// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;

public abstract class AbstractSessionIdManager extends AbstractLifeCycle implements SessionIdManager
{
    private final static String __NEW_SESSION_ID="org.eclipse.jetty.server.newSessionId";  
    protected final static String SESSION_ID_RANDOM_ALGORITHM = "SHA1PRNG";
    protected final static String SESSION_ID_RANDOM_ALGORITHM_ALT = "IBMSecureRandom";
    
    protected Random _random;
    protected boolean _weakRandom;
    protected String _workerName;
    protected final Server _server;
    
    
    public AbstractSessionIdManager(Server server)
    {
        _server=server;
    }
    
    
    public AbstractSessionIdManager(Server server, Random random)
    {
        _random=random;
        _server=server;
    }

    public String getWorkerName()
    {
        return _workerName;
    }
    
    public void setWorkerName (String name)
    {
        _workerName=name;
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
    /** 
     * Create a new session id if necessary.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#newSessionId(javax.servlet.http.HttpServletRequest, long)
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
                if (request!=null && request.getRemoteAddr()!=null)
                    r^=request.getRemoteAddr().hashCode();
                if (r<0)
                    r=-r;
                id=Long.toString(r,36);
                
                //add in the id of the node to ensure unique id across cluster
                //NOTE this is different to the node suffix which denotes which node the request was received on
                id=_workerName + id;
            }

            request.setAttribute(__NEW_SESSION_ID,id);
            return id;
        }
    }

    
    @Override
    public void doStart()
    {
       initRandom();
    }

    
    
    
    /**
     * Set up a random number generator for the sessionids.
     * 
     * By preference, use a SecureRandom but allow to be injected.
     */
    public void initRandom ()
    {
        if (_random==null)
        {
            try
            {
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
    }
}
