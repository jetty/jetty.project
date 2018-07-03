//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.hazelcast.session;

import com.hazelcast.core.IMap;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Session data stored in Hazelcast
 */
@ManagedObject
public class HazelcastSessionDataStore
    extends AbstractSessionDataStore
    implements SessionDataStore
{

    private  final static Logger LOG = Log.getLogger( "org.eclipse.jetty.server.session");

    private IMap<String, SessionData> sessionDataMap;

    public HazelcastSessionDataStore()
    {
        // no op
    }

    @Override
    public SessionData load( String id )
        throws Exception
    {

        final AtomicReference<SessionData> reference = new AtomicReference<>();
        final AtomicReference<Exception> exception = new AtomicReference<>();

        //ensure the load runs in the context classloader scope
        _context.run( () -> {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug( "Loading session {} from hazelcast", id );

                SessionData sd = sessionDataMap.get( getCacheKey( id ) );
                reference.set(sd);
            }
            catch (Exception e)
            {
                exception.set(new UnreadableSessionDataException(id, _context, e));
            }
        } );

        if (exception.get() != null)
        {
            throw exception.get();
        }
        return reference.get();
    }

    @Override
    public boolean delete( String id )
        throws Exception
    {
        return sessionDataMap == null ? false : sessionDataMap.remove( getCacheKey( id ) ) != null;
    }

    public IMap<String, SessionData> getSessionDataMap()
    {
        return sessionDataMap;
    }

    public void setSessionDataMap( IMap<String, SessionData> sessionDataMap )
    {
        this.sessionDataMap = sessionDataMap;
    }

    @Override
    public void initialize( SessionContext context )
        throws Exception
    {
        _context = context;
    }

    @Override
    public void doStore( String id, SessionData data, long lastSaveTime )
        throws Exception
    {
        this.sessionDataMap.set( getCacheKey( id ), data);
    }

    @Override
    public boolean isPassivating()
    {
        return true;
    }

    @Override
    public Set<String> doGetExpired( Set<String> candidates )
    {
        if (candidates == null || candidates.isEmpty())
        {
            return Collections.emptySet();
        }
        
        long now = System.currentTimeMillis();
        return candidates.stream().filter( candidate -> {
            
            if (LOG.isDebugEnabled())
                LOG.debug( "Checking expiry for candidate {}", candidate );
            
            try
            {
                SessionData sd = load(candidate);

                //if the session no longer exists
                if (sd == null)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug( "Session {} does not exist in Hazelcast", candidate );
                    }
                    return true;
                }
                else
                {
                    if (_context.getWorkerName().equals(sd.getLastNode()))
                    {
                        //we are its manager, add it to the expired set if it is expired now
                        if ((sd.getExpiry() > 0 ) && sd.getExpiry() <= now)
                        {
                            if (LOG.isDebugEnabled())
                            {
                                LOG.debug( "Session {} managed by {} is expired", candidate, _context.getWorkerName() );
                            }
                            return true;
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
                            {
                                return true;
                            }
                        }
                        else
                        {
                            if ((sd.getExpiry() > 0 ) && sd.getExpiry() < (now - (1000L * _gracePeriodSec)))
                            {
                                return true;
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn("Error checking if candidate {} is expired so expire it", candidate, e);
                return true;
            }
            return false;
        } ).collect( Collectors.toSet() );
    }

    @Override
    public boolean exists( String id )
    throws Exception
    {
        //TODO find way to do query without pulling in whole session data
        SessionData sd = load(id);
        if (sd == null)
            return false;

        if (sd.getExpiry() <= 0)
            return true; //never expires
        else
            return sd.getExpiry() > System.currentTimeMillis(); //not expired yet
    }

    public String getCacheKey( String id )
    {
        return _context.getCanonicalContextPath() + "_" + _context.getVhost() + "_" + id;
    }
}
