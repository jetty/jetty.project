//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.session.infinispan;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.infinispan.commons.api.BasicCache;

/**
 * InfinispanSessionDataStore
 *
 *
 */
public class InfinispanSessionDataStore extends AbstractSessionDataStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");



    /**
     * Clustered cache of sessions
     */
    private BasicCache<String, Object> _cache;

    
    private int _infinispanIdleTimeoutSec;
    

    /**
     * Get the clustered cache instance.
     * 
     * @return the cache
     */
    public BasicCache<String, Object> getCache() 
    {
        return _cache;
    }

    
    
    /**
     * Set the clustered cache instance.
     * 
     * @param cache the cache
     */
    public void setCache (BasicCache<String, Object> cache) 
    {
        this._cache = cache;
    }

    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {  
        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        
        Runnable load = new Runnable()
        {
            public void run ()
            {
                try
                {

                    if (LOG.isDebugEnabled())
                        LOG.debug("Loading session {} from infinispan", id);
     
                    SessionData sd = (SessionData)_cache.get(getCacheKey(id));
                    reference.set(sd);
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        
        //ensure the load runs in the context classloader scope
        _context.run(load);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Deleting session with id {} from infinispan", id);
        return (_cache.remove(getCacheKey(id)) != null);
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set)
     */
    @Override
    public Set<String> doGetExpired(Set<String> candidates)
    {
       if (candidates == null  || candidates.isEmpty())
           return candidates;
       
       long now = System.currentTimeMillis();
       
       Set<String> expired = new HashSet<String>();
       
       //TODO if there is NOT an idle timeout set on entries in infinispan, need to check other sessions
       //that are not currently in the SessionDataStore (eg they've been passivated)
       
       for (String candidate:candidates)
       {
           if (LOG.isDebugEnabled())
               LOG.debug("Checking expiry for candidate {}", candidate);
           try
           {
               SessionData sd = load(candidate);

               //if the session no longer exists
               if (sd == null)
               {
                   expired.add(candidate);
                   if (LOG.isDebugEnabled())
                       LOG.debug("Session {} does not exist in infinispan", candidate);
               }
               else
               {
                   if (_context.getWorkerName().equals(sd.getLastNode()))
                   {
                       //we are its manager, add it to the expired set if it is expired now
                       if ((sd.getExpiry() > 0 ) && sd.getExpiry() <= now)
                       {
                           expired.add(candidate);
                           if (LOG.isDebugEnabled())
                               LOG.debug("Session {} managed by {} is expired", candidate, _context.getWorkerName());
                       }
                   }
                   else
                   {
                       //if we are not the session's manager, only expire it iff:
                       // this is our first expiryCheck and the session expired a long time ago
                       //or
                       //the session expired at least one graceperiod ago
                       if (_lastExpiryCheckTime <=0)
                       {
                           if ((sd.getExpiry() > 0 ) && sd.getExpiry() < (now - (1000L * (3 * _gracePeriodSec))))
                               expired.add(candidate);
                       }
                       else
                       {
                           if ((sd.getExpiry() > 0 ) && sd.getExpiry() < (now - (1000L * _gracePeriodSec)))
                               expired.add(candidate);
                       }
                   }
               }
           }
           catch (Exception e)
           {
               LOG.warn("Error checking if candidate {} is expired", candidate, e);
           }
       }
       
       return expired;
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(String, SessionData, long)
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        try
        {
        //Put an idle timeout on the cache entry if the session is not immortal - 
        //if no requests arrive at any node before this timeout occurs, or no node 
        //scavenges the session before this timeout occurs, the session will be removed.
        //NOTE: that no session listeners can be called for this.
        if (data.getMaxInactiveMs() > 0 && getInfinispanIdleTimeoutSec() > 0)
            _cache.put(getCacheKey(id), data, -1, TimeUnit.MILLISECONDS, getInfinispanIdleTimeoutSec(), TimeUnit.SECONDS);
        else
            _cache.put(getCacheKey(id), data);

        if (LOG.isDebugEnabled())
            LOG.debug("Session {} saved to infinispan, expires {} ", id, data.getExpiry());
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    
    public String getCacheKey (String id)
    {
        return _context.getCanonicalContextPath()+"_"+_context.getVhost()+"_"+id;
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        //TODO run in the _context to ensure classloader is set
        try 
        {
           Class<?> remoteClass = Thread.currentThread().getContextClassLoader().loadClass("org.infinispan.client.hotrod.RemoteCache");
           if (remoteClass.isAssignableFrom(_cache.getClass()))
           {
               return true;
           }
           return false;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
     */
    @Override
    public boolean exists(String id) throws Exception
    {
        // TODO find a better way to do this that does not pull into memory the
        // whole session object
        final AtomicReference<Boolean> reference = new AtomicReference<Boolean>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();

        Runnable load = new Runnable()
        {
            public void run ()
            {
                try
                {
                    SessionData sd = load(id);
                    if (sd == null)
                    {
                        reference.set(Boolean.FALSE);
                        return;
                    }

                    if (sd.getExpiry() <= 0)
                        reference.set(Boolean.TRUE); //never expires
                    else
                        reference.set(Boolean.valueOf(sd.getExpiry() > System.currentTimeMillis())); //not expired yet
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        
        //ensure the load runs in the context classloader scope
        _context.run(load);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }



    /**
     * @param sec the infinispan-specific idle timeout in sec or 0 if not set
     */
    public void setInfinispanIdleTimeoutSec (int sec)
    {
        _infinispanIdleTimeoutSec = sec;
    }
    
    public int getInfinispanIdleTimeoutSec ()
    {
        return _infinispanIdleTimeoutSec;
    }



    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#toString()
     */
    @Override
    public String toString()
    {
        return String.format("%s[cache=%s,idleTimeoutSec=%d]",super.toString(), (_cache==null?"":_cache.getName()),_infinispanIdleTimeoutSec);
    }
    
    
}
