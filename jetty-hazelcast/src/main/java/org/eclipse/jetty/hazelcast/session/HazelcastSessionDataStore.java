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
import com.hazelcast.query.Predicate;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
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

    /**
     * {@link ExpiredSessionPredicate} use it to find the expired sessions that expired a long time ago.
     */
    private ExpiredSessionPredicate<String, SessionData> predicate;

    public static final ExpiredSessionPredicate<String, SessionData> DEFAULT_EXPIRED_SESSION_PREDICATE =
        new ExpiredSessionPredicate<String, SessionData>() {
            private HazelcastSessionDataStoreContext context;
            @Override
            public void setHazelcastSessionDataStoreContext( HazelcastSessionDataStoreContext context )
            {
                this.context = context;
            }

            @Override
            public boolean apply( Map.Entry<String, SessionData> mapEntry )
            {
                SessionData sessionData = mapEntry.getValue();
                long now = System.currentTimeMillis();
                if ( context.getSessionContext().getWorkerName().equals( sessionData.getLastNode() ) )
                {
                    //we are its manager, add it to the expired set if it is expired now
                    if ( ( sessionData.getExpiry() > 0 ) && sessionData.getExpiry() <= now )
                    {
                        if ( LOG.isDebugEnabled() )
                        {
                            LOG.debug( "Session {} managed by {} is expired",
                                       sessionData.getId(),
                                       context.getSessionContext().getWorkerName() );
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
                    if ( context.getLastExpiryCheckTime() <= 0 )
                    {
                        if ( ( sessionData.getExpiry() > 0 ) //
                            && sessionData.getExpiry() < ( now - ( 1000L * ( 3 * context.getGracePeriodSec() ) ) ) )
                        {
                            return true;
                        }
                    }
                    else
                    {
                        if ( ( sessionData.getExpiry() > 0 ) //
                            && sessionData.getExpiry() < ( now - ( 1000L * context.getGracePeriodSec() ) ) )
                        {
                            return true;
                        }
                    }
                }
                return false;
            }
        };


    /**
     * <p>
     * if <code>true</code> when calling {@link #getExpired(Set)} the hazelcast session store will fetch the store as well
     * for expired session
     * </p>
     */
    private boolean findExpiredSession;

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
                {
                    LOG.debug( "Loading session {} from hazelcast", id );
                }
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

    public boolean isFindExpiredSession()
    {
        return findExpiredSession;
    }

    public void setFindExpiredSession( boolean findExpiredSession )
    {
        this.findExpiredSession = findExpiredSession;
    }

    @Override
    public Set<String> doGetExpired( Set<String> candidates )
    {
        Set<String> expired = new HashSet<>();

        //check candidates that were not found to be expired, definitely they no longer exist and they should be expired

        expired.addAll( candidates.stream().filter( candidate -> {
            
            if (LOG.isDebugEnabled())
            {
                LOG.debug( "Checking expiry for candidate {}", candidate );
            }
            if(expired.contains( candidate ))
            {
                return false;
            }
            try
            {
                boolean hasCandidate = sessionDataMap.containsKey( getCacheKey( candidate ) );
                //if the session no longer exists
                if(!hasCandidate)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug( "Session {} does not exist in Hazelcast", candidate );
                    }
                    return true;
                }
            }
            catch (Exception e)
            {
                LOG.warn("Error checking if candidate {} is expired so expire it", candidate, e);
                return true;
            }
            return false;
        } ).collect(Collectors.toSet()));

        if(predicate != null)
        {
            predicate.setHazelcastSessionDataStoreContext( new HazelcastSessionDataStoreContext( _context,
                                                                                                 _lastExpiryCheckTime,
                                                                                                 _gracePeriodSec ) );
            expired.addAll( sessionDataMap //
                                .values( predicate ) //
                                .stream() //
                                .map( sessionData -> sessionData.getId() ) //
                                .collect( Collectors.toSet() ) );

        }

        return expired;
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
            return (Boolean.valueOf(sd.getExpiry() > System.currentTimeMillis())); //not expired yet
    }

    public String getCacheKey( String id )
    {
        return _context.getCanonicalContextPath() + "_" + _context.getVhost() + "_" + id;
    }

    public void setPredicate( ExpiredSessionPredicate<String, SessionData> predicate ) {
        this.predicate = predicate;
    }

    public static class HazelcastSessionDataStoreContext {
        private SessionContext sessionContext;
        private long _lastExpiryCheckTime;
        private long _gracePeriodSec;

        private HazelcastSessionDataStoreContext( SessionContext sessionContext, long _lastExpiryCheckTime,
                                                 long _gracePeriodSec )
        {
            this.sessionContext = sessionContext;
            this._lastExpiryCheckTime = _lastExpiryCheckTime;
            this._gracePeriodSec = _gracePeriodSec;
        }

        public SessionContext getSessionContext()
        {
            return sessionContext;
        }

        public long getLastExpiryCheckTime()
        {
            return _lastExpiryCheckTime;
        }

        public long getGracePeriodSec()
        {
            return _gracePeriodSec;
        }
    }

    interface ExpiredSessionPredicate<String,SessionData> extends Predicate<String,SessionData> {
        @Override
        boolean apply( Map.Entry<String, SessionData> mapEntry );

        void setHazelcastSessionDataStoreContext( HazelcastSessionDataStoreContext context);
    }
}
