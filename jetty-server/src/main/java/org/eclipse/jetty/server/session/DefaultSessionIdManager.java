//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    public static final String __NEW_SESSION_ID = "org.eclipse.jetty.server.newSessionId";

    protected static final AtomicLong COUNTER = new AtomicLong();

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
        _server = server;
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
        _server = server;
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
    public synchronized void setRandom(Random random)
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
     *
     * @see org.eclipse.jetty.server.SessionIdManager#newSessionId(javax.servlet.http.HttpServletRequest, long)
     */
    @Override
    public String newSessionId(HttpServletRequest request, long created)
    {
        if (request == null)
            return newSessionId(created);

        // A requested session ID can only be used if it is in use already.
        String requestedId = request.getRequestedSessionId();
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

        synchronized (_random)
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
                    if (_random instanceof SecureRandom)
                    {
                        SecureRandom secure = (SecureRandom)_random;
                        secure.setSeed(secure.generateSeed(8));
                    }
                    else
                    {
                        _random.setSeed(_random.nextLong() ^ System.currentTimeMillis() ^ seedTerm ^ Runtime.getRuntime().freeMemory());
                    }
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

    /**
     * @see org.eclipse.jetty.server.SessionIdManager#isIdInUse(java.lang.String)
     */
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
            for (SessionHandler manager : getSessionHandlers())
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
            LOG.warn("Problem checking if id {} is in use", id);
            LOG.warn(e);
            return false;
        }
    }

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_server == null)
            throw new IllegalStateException("No Server for SessionIdManager");

        initRandom();

        if (_workerName == null)
        {
            String inst = System.getenv("JETTY_WORKER_INSTANCE");
            _workerName = "node" + (inst == null ? "0" : inst);
        }

        LOG.info("DefaultSessionIdManager workerName={}", _workerName);
        _workerAttr = (_workerName != null && _workerName.startsWith("$")) ? _workerName.substring(1) : null;

        if (_houseKeeper == null)
        {
            LOG.info("No SessionScavenger set, using defaults");
            _ownHouseKeeper = true;
            _houseKeeper = new HouseKeeper();
            _houseKeeper.setSessionIdManager(this);
            addBean(_houseKeeper, true);
        }

        _houseKeeper.start();
    }

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _houseKeeper.stop();
        if (_ownHouseKeeper)
        {
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
    public String getExtendedId(String clusterId, HttpServletRequest request)
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
     *
     * @see org.eclipse.jetty.server.SessionIdManager#expireAll(java.lang.String)
     */
    @Override
    public void expireAll(String id)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Expiring {}", id);

        for (SessionHandler manager : getSessionHandlers())
        {
            manager.invalidate(id);
        }
    }

    @Override
    public void invalidateAll(String id)
    {
        //tell all contexts that may have a session object with this id to
        //get rid of them
        for (SessionHandler manager : getSessionHandlers())
        {
            manager.invalidate(id);
        }
    }

    /**
     * Generate a new id for a session and update across
     * all SessionManagers.
     *
     * @see org.eclipse.jetty.server.SessionIdManager#renewSessionId(java.lang.String, java.lang.String, javax.servlet.http.HttpServletRequest)
     */
    @Override
    public String renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());

        //TODO how to handle request for old id whilst id change is happening?

        //tell all contexts to update the id 
        for (SessionHandler manager : getSessionHandlers())
        {
            manager.renewSessionId(oldClusterId, oldNodeId, newClusterId, getExtendedId(newClusterId, request));
        }

        return newClusterId;
    }

    /**
     * Get SessionHandler for every context.
     *
     * @return all SessionHandlers that are running
     */
    @Override
    public Set<SessionHandler> getSessionHandlers()
    {
        Set<SessionHandler> handlers = new HashSet<>();
        Handler[] tmp = _server.getChildHandlersByClass(SessionHandler.class);
        if (tmp != null)
        {
            for (Handler h : tmp)
            {
                //This method can be called on shutdown when the handlers are STOPPING, so only
                //check that they are not already stopped
                if (!h.isStopped() && !h.isFailed())
                    handlers.add((SessionHandler)h);
            }
        }
        return handlers;
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
        return String.format("%s[worker=%s]", super.toString(), _workerName);
    }
}
