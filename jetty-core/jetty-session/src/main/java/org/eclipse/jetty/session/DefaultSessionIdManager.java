//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DefaultSessionIdManager
 *
 * Manages session ids to ensure each session id within a context is unique, and that
 * session ids can be shared across contexts (but not session contents).
 *
 * There is only 1 session id manager per Server instance.
 *
 * Runs a HouseKeeper thread to periodically check for expired Sessions.
 *
 * @see HouseKeeper
 */
@ManagedObject
public class DefaultSessionIdManager extends ContainerLifeCycle implements SessionIdManager
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSessionIdManager.class);

    public static final String __NEW_SESSION_ID = "org.eclipse.jetty.server.newSessionId";

    protected static final AtomicLong COUNTER = new AtomicLong();

    private final AutoLock _lock = new AutoLock();
    protected Random _random;
    protected boolean _weakRandom;
    protected String _workerName;
    protected String _workerAttr;
    protected long _reseed = 100000L;
    protected Server _server;
    protected HouseKeeper _houseKeeper;
    protected boolean _ownHouseKeeper;

    /**
     * @param server the server associated with the id manager
     */
    public DefaultSessionIdManager(Server server)
    {
        _server = Objects.requireNonNull(server);
    }

    /**
     * @param server the server associated with the id manager
     * @param random a random number generator to use for ids
     */
    public DefaultSessionIdManager(Server server, Random random)
    {
        this(server);
        _random = random;
    }

    /**
     * @param server the server associated with this id manager
     */
    public void setServer(Server server)
    {
        _server = Objects.requireNonNull(server);
    }

    /**
     * @return the server associated with this id manager
     */
    public Server getServer()
    {
        return _server;
    }

    /**
     * @param houseKeeper the housekeeper
     */
    @Override
    public void setSessionHouseKeeper(HouseKeeper houseKeeper)
    {
        updateBean(_houseKeeper, houseKeeper);
        _houseKeeper = houseKeeper;
        _houseKeeper.setSessionIdManager(this);
    }

    /**
     * @return the housekeeper
     */
    @Override
    public HouseKeeper getSessionHouseKeeper()
    {
        return _houseKeeper;
    }

    /**
     * Get the workname. If set, the workername is dot appended to the session
     * ID and can be used to assist session affinity in a load balancer.
     *
     * @return name or null
     */
    @Override
    @ManagedAttribute(value = "unique name for this node", readonly = true)
    public String getWorkerName()
    {
        return _workerName;
    }

    /**
     * Set the workername. If set, the workername is dot appended to the session
     * ID and can be used to assist session affinity in a load balancer.
     * A worker name starting with $ is used as a request attribute name to
     * lookup the worker name that can be dynamically set by a request
     * Customizer.
     *
     * @param workerName the name of the worker, if null it is coerced to empty string
     */
    public void setWorkerName(String workerName)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        if (workerName == null)
            _workerName = "";
        else
        {
            if (workerName.contains("."))
                throw new IllegalArgumentException("Name cannot contain '.'");
            _workerName = workerName;
        }
    }

    /**
     * @return the random number generator
     */
    public Random getRandom()
    {
        return _random;
    }

    /**
     * @param random a random number generator for generating ids
     */
    public void setRandom(Random random)
    {
        _random = random;
        _weakRandom = false;
    }

    /**
     * @return the reseed probability
     */
    public long getReseed()
    {
        return _reseed;
    }

    /**
     * Set the reseed probability.
     *
     * @param reseed If non zero then when a random long modulo the reseed value == 1, the {@link SecureRandom} will be reseeded.
     */
    public void setReseed(long reseed)
    {
        _reseed = reseed;
    }

    /**
     * Create a new session id if necessary.
     */
    @Override
    public String newSessionId(Request request, String requestedId, long created)
    {
        if (request == null)
            return newSessionId(created);

        // A requested session ID can only be used if it is in use already.
        if (requestedId != null)
        {
            String clusterId = getId(requestedId);
            if (isIdInUse(clusterId))
                return clusterId;
        }

        // Else reuse any new session ID already defined for this request.
        String newId = (String)request.getAttribute(__NEW_SESSION_ID);
        if (newId != null && isIdInUse(newId))
            return newId;

        // pick a new unique ID!
        String id = newSessionId(request.hashCode());

        request.setAttribute(__NEW_SESSION_ID, id);
        return id;
    }

    /**
     * @param seedTerm the seed for RNG
     * @return a new unique session id
     */
    public String newSessionId(long seedTerm)
    {
        // pick a new unique ID!
        String id = null;

        try (AutoLock ignored = _lock.lock())
        {
            while (id == null || id.length() == 0)
            {
                long r0 = _weakRandom
                    ? (hashCode() ^ Runtime.getRuntime().freeMemory() ^ _random.nextInt() ^ ((seedTerm) << 32))
                    : _random.nextLong();
                if (r0 < 0)
                    r0 = -r0;

                // random chance to reseed
                if (_reseed > 0 && (r0 % _reseed) == 1L)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Reseeding {}", this);
                    if (_random instanceof SecureRandom secure)
                        secure.setSeed(secure.generateSeed(8));
                    else
                        _random.setSeed(_random.nextLong() ^ System.currentTimeMillis() ^ seedTerm ^ Runtime.getRuntime().freeMemory());
                }

                long r1 = _weakRandom
                    ? (hashCode() ^ Runtime.getRuntime().freeMemory() ^ _random.nextInt() ^ ((seedTerm) << 32))
                    : _random.nextLong();
                if (r1 < 0)
                    r1 = -r1;

                id = Long.toString(r0, 36) + Long.toString(r1, 36);

                //add in the id of the node to ensure unique id across cluster
                //NOTE this is different to the node suffix which denotes which node the request was received on
                if (!StringUtil.isBlank(_workerName))
                    id = _workerName + id;

                id = id + COUNTER.getAndIncrement();
            }
        }
        return id;
    }
    
    @Override
    public boolean isIdInUse(String id)
    {
        if (id == null)
            return false;

        boolean inUse = false;
        if (LOG.isDebugEnabled())
            LOG.debug("Checking {} is in use by at least one context", id);

        try
        {
            for (SessionManager manager : getSessionManagers())
            {
                if (manager.isIdInUse(id))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Context {} reports id in use", manager);
                    inUse = true;
                    break;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Checked {}, in use: {}", id, inUse);
            return inUse;
        }
        catch (Exception e)
        {
            LOG.warn("Problem checking if id {} is in use", id, e);
            return false;
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        initRandom();

        if (_workerName == null)
        {
            String inst = System.getenv("JETTY_WORKER_INSTANCE");
            _workerName = "node" + (inst == null ? "0" : inst);
        }

        _workerAttr = _workerName.startsWith("$") ? _workerName.substring(1) : null;

        if (_houseKeeper == null)
        {
            _ownHouseKeeper = true;
            _houseKeeper = new HouseKeeper();
            _houseKeeper.setSessionIdManager(this);
            addBean(_houseKeeper, true);
        }

        LOG.info("Session workerName={}", _workerName);
        _houseKeeper.start();
    }

    @Override
    protected void doStop() throws Exception
    {
        _houseKeeper.stop();
        if (_ownHouseKeeper)
        {
            removeBean(_houseKeeper);
            _houseKeeper = null;
        }
        _random = null;
    }

    /**
     * Set up a random number generator for the sessionids.
     *
     * By preference, use a SecureRandom but allow to be injected.
     */
    public void initRandom()
    {
        if (_random == null)
        {
            try
            {
                _random = new SecureRandom();
            }
            catch (Exception e)
            {
                LOG.warn("Could not generate SecureRandom for session-id randomness", e);
                _random = new Random();
                _weakRandom = true;
            }
        }
        else
            _random.setSeed(_random.nextLong() ^ System.currentTimeMillis() ^ hashCode() ^ Runtime.getRuntime().freeMemory());
    }

    /**
     * Get the session ID with any worker ID.
     *
     * @param clusterId the cluster id
     * @param request the request
     * @return sessionId plus any worker ID.
     */
    @Override
    public String getExtendedId(String clusterId, Request request)
    {
        if (!StringUtil.isBlank(_workerName))
        {
            if (_workerAttr == null)
                return clusterId + '.' + _workerName;

            String worker = (String)request.getAttribute(_workerAttr);
            if (worker != null)
                return clusterId + '.' + worker;
        }

        return clusterId;
    }

    /**
     * Get the session ID without any worker ID.
     *
     * @param extendedId the session id with the worker extension
     * @return sessionId without any worker ID.
     */
    @Override
    public String getId(String extendedId)
    {
        int dot = extendedId.lastIndexOf('.');
        return (dot > 0) ? extendedId.substring(0, dot) : extendedId;
    }

    /**
     * Remove an id from use by telling all contexts to remove a session with this id.
     */
    @Override
    public void expireAll(String id)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Expiring {}", id);

        for (SessionManager manager : getSessionManagers())
        {
            try
            {
                manager.invalidate(id);
            }
            catch (Exception e)
            {
                LOG.warn("Problem expiring session {} across contexts", id, e);
            }
        }
    }

    @Override
    public void invalidateAll(String id)
    {
        //tell all contexts that may have a session object with this id to
        //get rid of them
        for (SessionManager manager : getSessionManagers())
        {
            try
            {
                manager.invalidate(id);
            }
            catch (Exception e)
            {
                LOG.warn("Problem invalidating session {} across contexts", id, e);
            }
        }
    }
    
    public void scavenge()
    {
        //tell all contexts that may have a session object with this id to
        //get rid of them
        for (SessionManager manager : getSessionManagers())
        {
            try
            {
                manager.scavenge();
            }
            catch (Exception e)
            {
                LOG.warn("Problem scavenging sessions across contexts", e);
            }
        }
    }

    /**
     * Generate a new id for a session and update across
     * all SessionManagers.
     */
    @Override
    public String renewSessionId(String oldClusterId, String oldNodeId, Request request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());

        //tell all contexts to update the id 
        for (SessionManager manager : getSessionManagers())
        {
            try
            {
                manager.renewSessionId(oldClusterId, oldNodeId, newClusterId, getExtendedId(newClusterId, request));
            }
            catch (Exception e)
            {
                LOG.warn("Problem renewing session id {} to {}", oldClusterId, newClusterId, e);
            }
        }

        return newClusterId;
    }

    /**
     * Get SessionManager for every context.
     *
     * @return all SessionManagers that are running
     */
    public Set<SessionManager> getSessionManagers()
    {
        Set<SessionManager> managers = new HashSet<>();

        Collection<SessionManager> tmp = _server.getContainedBeans(SessionManager.class);
        for (SessionManager sm : tmp)
        {
            //This method can be called on shutdown when the handlers are STOPPING, so only
            //check that they are not already stopped
            if (!sm.isStopped() && !sm.isFailed())
                managers.add(sm);
        }

        return managers;
    }

    @Override
    public String toString()
    {
        return String.format("%s[worker=%s]", super.toString(), _workerName);
    }
}
