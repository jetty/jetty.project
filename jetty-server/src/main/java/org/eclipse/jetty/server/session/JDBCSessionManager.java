//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.SessionTrackingMode;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * JDBCSessionManager
 *
 * SessionManager that persists sessions to a database to enable clustering.
 *
 * Session data is persisted to the JettySessions table:
 *
 * rowId (unique in cluster: webapp name/path + virtualhost + sessionId)
 * contextPath (of the context owning the session)
 * sessionId (unique in a context)
 * lastNode (name of node last handled session)
 * accessTime (time in milliseconds session was accessed)
 * lastAccessTime (previous time in milliseconds session was accessed)
 * createTime (time in milliseconds session created)
 * cookieTime (time in milliseconds session cookie created)
 * lastSavedTime (last time in milliseconds session access times were saved)
 * expiryTime (time in milliseconds that the session is due to expire)
 * map (attribute map)
 *
 * As an optimization, to prevent thrashing the database, we do not persist
 * the accessTime and lastAccessTime every time the session is accessed. Rather,
 * we write it out every so often. The frequency is controlled by the saveIntervalSec
 * field.
 */
public class JDBCSessionManager extends AbstractSessionManager
{
    private static final Logger LOG = Log.getLogger(JDBCSessionManager.class);

    private ConcurrentHashMap<String, AbstractSession> _sessions;
    protected JDBCSessionIdManager _jdbcSessionIdMgr = null;
    protected long _saveIntervalSec = 60; //only persist changes to session access times every 60 secs

    /**
     * SessionData
     *
     * Persistable data about a session.
     */
    public class SessionData
    {
        private final String _id;
        private String _rowId;
        private long _accessed;
        private long _lastAccessed;
        private long _maxIdleMs=-1;
        private long _cookieSet;
        private long _created;
        private Map<String,Object> _attributes;
        private String _lastNode;
        private String _canonicalContext;
        private long _lastSaved;
        private long _expiryTime;
        private String _virtualHost;

        public SessionData (String sessionId)
        {
            _id=sessionId;
            _created=System.currentTimeMillis();
            _accessed = _created;
            _attributes = new HashMap<String,Object>();
            _lastNode = getSessionIdManager().getWorkerName();
        }

        public SessionData (String sessionId,Map<String,Object> attributes)
        {
            _id=sessionId;
            _created=System.currentTimeMillis();
            _accessed = _created;
            _attributes = attributes;
            _lastNode = getSessionIdManager().getWorkerName();
        }

        public synchronized String getId ()
        {
            return _id;
        }

        public synchronized long getCreated ()
        {
            return _created;
        }

        protected synchronized void setCreated (long ms)
        {
            _created = ms;
        }

        public synchronized long getAccessed ()
        {
            return _accessed;
        }

        protected synchronized void setAccessed (long ms)
        {
            _accessed = ms;
        }


        public synchronized void setMaxIdleMs (long ms)
        {
            _maxIdleMs = ms;
        }

        public synchronized long getMaxIdleMs()
        {
            return _maxIdleMs;
        }

        public synchronized void setLastAccessed (long ms)
        {
            _lastAccessed = ms;
        }

        public synchronized long getLastAccessed()
        {
            return _lastAccessed;
        }

        public void setCookieSet (long ms)
        {
            _cookieSet = ms;
        }

        public synchronized long getCookieSet ()
        {
            return _cookieSet;
        }

        public synchronized void setRowId (String rowId)
        {
            _rowId=rowId;
        }

        protected synchronized String getRowId()
        {
            return _rowId;
        }

        protected synchronized Map<String,Object> getAttributeMap ()
        {
            return _attributes;
        }

        protected synchronized void setAttributeMap (Map<String,Object> map)
        {
            _attributes = map;
        }

        public synchronized void setLastNode (String node)
        {
            _lastNode=node;
        }

        public synchronized String getLastNode ()
        {
            return _lastNode;
        }

        public synchronized void setCanonicalContext(String str)
        {
            _canonicalContext=str;
        }

        public synchronized String getCanonicalContext ()
        {
            return _canonicalContext;
        }

        public synchronized long getLastSaved ()
        {
            return _lastSaved;
        }

        public synchronized void setLastSaved (long time)
        {
            _lastSaved=time;
        }

        public synchronized void setExpiryTime (long time)
        {
            _expiryTime=time;
        }

        public synchronized long getExpiryTime ()
        {
            return _expiryTime;
        }

        public synchronized void setVirtualHost (String vhost)
        {
            _virtualHost=vhost;
        }

        public synchronized String getVirtualHost ()
        {
            return _virtualHost;
        }

        @Override
        public String toString ()
        {
            return "Session rowId="+_rowId+",id="+_id+",lastNode="+_lastNode+
                            ",created="+_created+",accessed="+_accessed+
                            ",lastAccessed="+_lastAccessed+",cookieSet="+_cookieSet+
                            "lastSaved="+_lastSaved;
        }
    }



    /**
     * Session
     *
     * Session instance in memory of this node.
     */
    public class Session extends AbstractSession
    {
        private static final long serialVersionUID = 5208464051134226143L;
        private final SessionData _data;
        private boolean _dirty=false;

        /**
         * Session from a request.
         *
         * @param request
         */
        protected Session (HttpServletRequest request)
        {
            super(JDBCSessionManager.this,request);
            _data = new SessionData(getClusterId(),_jdbcAttributes);
            if (_dftMaxIdleSecs>0)
                _data.setMaxIdleMs(_dftMaxIdleSecs*1000L);
            _data.setCanonicalContext(canonicalize(_context.getContextPath()));
            _data.setVirtualHost(getVirtualHost(_context));
            int maxInterval=getMaxInactiveInterval();
            _data.setExpiryTime(maxInterval <= 0 ? 0 : (System.currentTimeMillis() + maxInterval*1000L));
        }

        /**
          * Session restored in database.
          * @param data
          */
         protected Session (long accessed, SessionData data)
         {
             super(JDBCSessionManager.this,data.getCreated(), accessed, data.getId());
             _data=data;
             if (_dftMaxIdleSecs>0)
                 _data.setMaxIdleMs(_dftMaxIdleSecs*1000L);
             _jdbcAttributes.putAll(_data.getAttributeMap());
             _data.setAttributeMap(_jdbcAttributes);
         }

         @Override
        public void setAttribute (String name, Object value)
         {
             super.setAttribute(name, value);
             _dirty=true;
         }

         @Override
        public void removeAttribute (String name)
         {
             super.removeAttribute(name);
             _dirty=true;
         }

         @Override
        protected void cookieSet()
         {
             _data.setCookieSet(_data.getAccessed());
         }

        /**
         * Entry to session.
         * Called by SessionHandler on inbound request and the session already exists in this node's memory.
         *
         * @see org.eclipse.jetty.server.session.AbstractSession#access(long)
         */
        @Override
        protected boolean access(long time)
        {
            if (super.access(time))
            {
                _data.setLastAccessed(_data.getAccessed());
                _data.setAccessed(time);

                int maxInterval=getMaxInactiveInterval();
                _data.setExpiryTime(maxInterval <= 0 ? 0 : (time + maxInterval*1000L));
                return true;
            }
            return false;
        }

        /**
         * Exit from session
         * @see org.eclipse.jetty.server.session.AbstractSession#complete()
         */
        @Override
        protected void complete()
        {
            super.complete();
            try
            {
                if (_dirty)
                {
                    //The session attributes have changed, write to the db, ensuring
                    //http passivation/activation listeners called
                    willPassivate();
                    updateSession(_data);
                    didActivate();
                }
                else if ((_data._accessed - _data._lastSaved) >= (getSaveInterval() * 1000L))
                {
                    updateSessionAccessTime(_data);
                }
            }
            catch (Exception e)
            {
                LOG.warn("Problem persisting changed session data id="+getId(), e);
            }
            finally
            {
                _dirty=false;
            }
        }

        @Override
        protected void timeout() throws IllegalStateException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Timing out session id="+getClusterId());
            super.timeout();
        }
    }




    /**
     * ClassLoadingObjectInputStream
     *
     *
     */
    protected class ClassLoadingObjectInputStream extends ObjectInputStream
    {
        public ClassLoadingObjectInputStream(java.io.InputStream in) throws IOException
        {
            super(in);
        }

        public ClassLoadingObjectInputStream () throws IOException
        {
            super();
        }

        @Override
        public Class<?> resolveClass (java.io.ObjectStreamClass cl) throws IOException, ClassNotFoundException
        {
            try
            {
                return Class.forName(cl.getName(), false, Thread.currentThread().getContextClassLoader());
            }
            catch (ClassNotFoundException e)
            {
                return super.resolveClass(cl);
            }
        }
    }




    /**
     * Set the time in seconds which is the interval between
     * saving the session access time to the database.
     *
     * This is an optimization that prevents the database from
     * being overloaded when a session is accessed very frequently.
     *
     * On session exit, if the session attributes have NOT changed,
     * the time at which we last saved the accessed
     * time is compared to the current accessed time. If the interval
     * is at least saveIntervalSecs, then the access time will be
     * persisted to the database.
     *
     * If any session attribute does change, then the attributes and
     * the accessed time are persisted.
     *
     * @param sec
     */
    public void setSaveInterval (long sec)
    {
        _saveIntervalSec=sec;
    }

    public long getSaveInterval ()
    {
        return _saveIntervalSec;
    }



    /**
     * A method that can be implemented in subclasses to support
     * distributed caching of sessions. This method will be
     * called whenever the session is written to the database
     * because the session data has changed.
     *
     * This could be used eg with a JMS backplane to notify nodes
     * that the session has changed and to delete the session from
     * the node's cache, and re-read it from the database.
     * @param session
     */
    public void cacheInvalidate (Session session)
    {

    }


    /**
     * A session has been requested by it's id on this node.
     *
     * Load the session by id AND context path from the database.
     * Multiple contexts may share the same session id (due to dispatching)
     * but they CANNOT share the same contents.
     *
     * Check if last node id is my node id, if so, then the session we have
     * in memory cannot be stale. If another node used the session last, then
     * we need to refresh from the db.
     *
     * NOTE: this method will go to the database, so if you only want to check
     * for the existence of a Session in memory, use _sessions.get(id) instead.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#getSession(java.lang.String)
     */
    @Override
    public Session getSession(String idInCluster)
    {
        Session session = (Session)_sessions.get(idInCluster);

        synchronized (this)
        {
            try
            {
                //check if we need to reload the session -
                //as an optimization, don't reload on every access
                //to reduce the load on the database. This introduces a window of
                //possibility that the node may decide that the session is local to it,
                //when the session has actually been live on another node, and then
                //re-migrated to this node. This should be an extremely rare occurrence,
                //as load-balancers are generally well-behaved and consistently send
                //sessions to the same node, changing only iff that node fails.
                SessionData data = null;
                long now = System.currentTimeMillis();
                if (LOG.isDebugEnabled())
                {
                    if (session==null)
                        LOG.debug("getSession("+idInCluster+"): not in session map,"+
                                " now="+now+
                                " lastSaved="+(session==null?0:session._data._lastSaved)+
                                " interval="+(_saveIntervalSec * 1000L));
                    else
                        LOG.debug("getSession("+idInCluster+"): in session map, "+
                                " now="+now+
                                " lastSaved="+(session==null?0:session._data._lastSaved)+
                                " interval="+(_saveIntervalSec * 1000L)+
                                " lastNode="+session._data.getLastNode()+
                                " thisNode="+getSessionIdManager().getWorkerName()+
                                " difference="+(now - session._data._lastSaved));
                }

                if (session==null || ((now - session._data._lastSaved) >= (_saveIntervalSec * 1000L)))
                {
                    LOG.debug("getSession("+idInCluster+"): no session in session map or stale session. Reloading session data from db.");
                    data = loadSession(idInCluster, canonicalize(_context.getContextPath()), getVirtualHost(_context));
                }
                else if ((now - session._data._lastSaved) >= (_saveIntervalSec * 1000L))
                {
                    LOG.debug("getSession("+idInCluster+"): stale session. Reloading session data from db.");
                    data = loadSession(idInCluster, canonicalize(_context.getContextPath()), getVirtualHost(_context));
                }
                else
                {
                    LOG.debug("getSession("+idInCluster+"): session in session map");
                    data = session._data;
                }

                if (data != null)
                {
                    if (!data.getLastNode().equals(getSessionIdManager().getWorkerName()) || session==null)
                    {
                        //if the session has no expiry, or it is not already expired
                        if (data._expiryTime <= 0 || data._expiryTime > now)
                        {
                            LOG.debug("getSession("+idInCluster+"): lastNode="+data.getLastNode()+" thisNode="+getSessionIdManager().getWorkerName());
                            data.setLastNode(getSessionIdManager().getWorkerName());
                            //session last used on a different node, or we don't have it in memory
                            session = new Session(now,data);
                            _sessions.put(idInCluster, session);
                            session.didActivate();
                            //TODO is this the best way to do this? Or do this on the way out using
                            //the _dirty flag?
                            updateSessionNode(data);
                        }
                        else
                            if (LOG.isDebugEnabled()) LOG.debug("getSession("+idInCluster+"): Session has expired");

                    }
                    else
                        if (LOG.isDebugEnabled()) LOG.debug("getSession("+idInCluster+"): Session not stale "+session._data);
                    //session in db shares same id, but is not for this context
                }
                else
                {
                    //No session in db with matching id and context path.
                    session=null;
                    if (LOG.isDebugEnabled()) LOG.debug("getSession("+idInCluster+"): No session in database matching id="+idInCluster);
                }

                return session;
            }
            catch (Exception e)
            {
                LOG.warn("Unable to load session from database", e);
                return null;
            }
        }
    }

    /**
     * Get the number of sessions.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#getSessions()
     */
    @Override
    public int getSessions()
    {
        int size = 0;
        synchronized (this)
        {
            size = _sessions.size();
        }
        return size;
    }


    /**
     * Start the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        if (_sessionIdManager==null)
            throw new IllegalStateException("No session id manager defined");

        _jdbcSessionIdMgr = (JDBCSessionIdManager)_sessionIdManager;
        
        _sessions = new ConcurrentHashMap<String, AbstractSession>();
        super.doStart();
    }


    /**
     * Stop the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStop()
     */
    @Override
    public void doStop() throws Exception
    {
        _sessions.clear();
        _sessions = null;

        super.doStop();
    }

    @Override
    protected void invalidateSessions()
    {
        //Do nothing - we don't want to remove and
        //invalidate all the sessions because this
        //method is called from doStop(), and just
        //because this context is stopping does not
        //mean that we should remove the session from
        //any other nodes
    }


    /**
     * Invalidate a session.
     *
     * @param idInCluster
     */
    protected void invalidateSession (String idInCluster)
    {
        Session session = null;
        synchronized (this)
        {
            session = (Session)_sessions.get(idInCluster);
        }

        if (session != null)
        {
            session.invalidate();
        }
    }

    /**
     * Delete an existing session, both from the in-memory map and
     * the database.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#removeSession(java.lang.String)
     */
    @Override
    protected boolean removeSession(String idInCluster)
    {
        synchronized (this)
        {
            Session session = (Session)_sessions.remove(idInCluster);
            try
            {
                if (session != null)
                    deleteSession(session._data);
            }
            catch (Exception e)
            {
                LOG.warn("Problem deleting session id="+idInCluster, e);
            }
            return session!=null;
        }
    }


    /**
     * Add a newly created session to our in-memory list for this node and persist it.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#addSession(org.eclipse.jetty.server.session.AbstractSession)
     */
    @Override
    protected void addSession(AbstractSession session)
    {
        if (session==null)
            return;

        synchronized (this)
        {
            _sessions.put(session.getClusterId(), session);
        }

        //TODO or delay the store until exit out of session? If we crash before we store it
        //then session data will be lost.
        try
        {
            session.willPassivate();
            storeSession(((JDBCSessionManager.Session)session)._data);
            session.didActivate();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to store new session id="+session.getId() , e);
        }
    }


    /**
     * Make a new Session.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#newSession(javax.servlet.http.HttpServletRequest)
     */
    @Override
    protected AbstractSession newSession(HttpServletRequest request)
    {
        return new Session(request);
    }

    /* ------------------------------------------------------------ */
    /** Remove session from manager
     * @param session The session to remove
     * @param invalidate True if {@link HttpSessionListener#sessionDestroyed(HttpSessionEvent)} and
     * {@link SessionIdManager#invalidateAll(String)} should be called.
     */
    @Override
    public void removeSession(AbstractSession session, boolean invalidate)
    {
        // Remove session from context and global maps
        boolean removed = false;

        synchronized (this)
        {
            //take this session out of the map of sessions for this context
            if (getSession(session.getClusterId()) != null)
            {
                removed = true;
                removeSession(session.getClusterId());
            }
        }

        if (removed)
        {
            // Remove session from all context and global id maps
            _sessionIdManager.removeSession(session);

            if (invalidate)
                _sessionIdManager.invalidateAll(session.getClusterId());

            if (invalidate && !_sessionListeners.isEmpty())
            {
                HttpSessionEvent event=new HttpSessionEvent(session);
                for (HttpSessionListener l : _sessionListeners)
                    l.sessionDestroyed(event);
            }
            if (!invalidate)
            {
                session.willPassivate();
            }
        }
    }


    /**
     * Expire any Sessions we have in memory matching the list of
     * expired Session ids.
     *
     * @param sessionIds
     */
    protected void expire (List<?> sessionIds)
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        //Remove any sessions we already have in memory that match the ids
        Thread thread=Thread.currentThread();
        ClassLoader old_loader=thread.getContextClassLoader();
        ListIterator<?> itor = sessionIds.listIterator();

        try
        {
            while (itor.hasNext())
            {
                String sessionId = (String)itor.next();
                if (LOG.isDebugEnabled())
                    LOG.debug("Expiring session id "+sessionId);

                Session session = (Session)_sessions.get(sessionId);
                if (session != null)
                {
                    session.timeout();
                    itor.remove();
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unrecognized session id="+sessionId);
                }
            }
        }
        catch (Throwable t)
        {
            LOG.warn("Problem expiring sessions", t);
        }
        finally
        {
            thread.setContextClassLoader(old_loader);
        }
    }


    /**
     * Load a session from the database
     * @param id
     * @return the session data that was loaded
     * @throws Exception
     */
    protected SessionData loadSession (final String id, final String canonicalContextPath, final String vhost)
    throws Exception
    {
        final AtomicReference<SessionData> _reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> _exception = new AtomicReference<Exception>();
        Runnable load = new Runnable()
        {
            @SuppressWarnings("unchecked")
            public void run()
            {
                SessionData data = null;
                Connection connection=null;
                PreparedStatement statement = null;
                try
                {
                    connection = getConnection();
                    statement = _jdbcSessionIdMgr._dbAdaptor.getLoadStatement(connection, id, canonicalContextPath, vhost);
                    ResultSet result = statement.executeQuery();
                    if (result.next())
                    {
                        data = new SessionData(id);
                        data.setRowId(result.getString(_jdbcSessionIdMgr._sessionTableRowId));
                        data.setCookieSet(result.getLong("cookieTime"));
                        data.setLastAccessed(result.getLong("lastAccessTime"));
                        data.setAccessed (result.getLong("accessTime"));
                        data.setCreated(result.getLong("createTime"));
                        data.setLastNode(result.getString("lastNode"));
                        data.setLastSaved(result.getLong("lastSavedTime"));
                        data.setExpiryTime(result.getLong("expiryTime"));
                        data.setCanonicalContext(result.getString("contextPath"));
                        data.setVirtualHost(result.getString("virtualHost"));

                        InputStream is = ((JDBCSessionIdManager)getSessionIdManager())._dbAdaptor.getBlobInputStream(result, "map");
                        ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream (is);
                        Object o = ois.readObject();
                        data.setAttributeMap((Map<String,Object>)o);
                        ois.close();

                        if (LOG.isDebugEnabled())
                            LOG.debug("LOADED session "+data);
                    }
                    _reference.set(data);
                }
                catch (Exception e)
                {
                    _exception.set(e);
                }
                finally
                {
                    if (connection!=null)
                    {
                        try { connection.close();}
                        catch(Exception e) { LOG.warn(e); }
                    }
                }
            }
        };

        if (_context==null)
            load.run();
        else
            _context.getContextHandler().handle(load);

        if (_exception.get()!=null)
            throw _exception.get();

        return _reference.get();
    }

    /**
     * Insert a session into the database.
     *
     * @param data
     * @throws Exception
     */
    protected void storeSession (SessionData data)
    throws Exception
    {
        if (data==null)
            return;

        //put into the database
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            String rowId = calculateRowId(data);

            long now = System.currentTimeMillis();
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_jdbcSessionIdMgr._insertSession);
            statement.setString(1, rowId); //rowId
            statement.setString(2, data.getId()); //session id
            statement.setString(3, data.getCanonicalContext()); //context path
            statement.setString(4, data.getVirtualHost()); //first vhost
            statement.setString(5, getSessionIdManager().getWorkerName());//my node id
            statement.setLong(6, data.getAccessed());//accessTime
            statement.setLong(7, data.getLastAccessed()); //lastAccessTime
            statement.setLong(8, data.getCreated()); //time created
            statement.setLong(9, data.getCookieSet());//time cookie was set
            statement.setLong(10, now); //last saved time
            statement.setLong(11, data.getExpiryTime());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(data.getAttributeMap());
            byte[] bytes = baos.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            statement.setBinaryStream(12, bais, bytes.length);//attribute map as blob

            statement.executeUpdate();
            data.setRowId(rowId); //set it on the in-memory data as well as in db
            data.setLastSaved(now);


            if (LOG.isDebugEnabled())
                LOG.debug("Stored session "+data);
        }
        finally
        {
            if (connection!=null)
                connection.close();
        }
    }


    /**
     * Update data on an existing persisted session.
     *
     * @param data
     * @throws Exception
     */
    protected void updateSession (SessionData data)
    throws Exception
    {
        if (data==null)
            return;

        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            long now = System.currentTimeMillis();
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_jdbcSessionIdMgr._updateSession);
            statement.setString(1, getSessionIdManager().getWorkerName());//my node id
            statement.setLong(2, data.getAccessed());//accessTime
            statement.setLong(3, data.getLastAccessed()); //lastAccessTime
            statement.setLong(4, now); //last saved time
            statement.setLong(5, data.getExpiryTime());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(data.getAttributeMap());
            byte[] bytes = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);

            statement.setBinaryStream(6, bais, bytes.length);//attribute map as blob
            statement.setString(7, data.getRowId()); //rowId
            statement.executeUpdate();

            data.setLastSaved(now);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated session "+data);
        }
        finally
        {
            if (connection!=null)
                connection.close();
        }
    }


    /**
     * Update the node on which the session was last seen to be my node.
     *
     * @param data
     * @throws Exception
     */
    protected void updateSessionNode (SessionData data)
    throws Exception
    {
        String nodeId = getSessionIdManager().getWorkerName();
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_jdbcSessionIdMgr._updateSessionNode);
            statement.setString(1, nodeId);
            statement.setString(2, data.getRowId());
            statement.executeUpdate();
            statement.close();
            if (LOG.isDebugEnabled())
                LOG.debug("Updated last node for session id="+data.getId()+", lastNode = "+nodeId);
        }
        finally
        {
            if (connection!=null)
                connection.close();
        }
    }

    /**
     * Persist the time the session was last accessed.
     *
     * @param data
     * @throws Exception
     */
    private void updateSessionAccessTime (SessionData data)
    throws Exception
    {
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            long now = System.currentTimeMillis();
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_jdbcSessionIdMgr._updateSessionAccessTime);
            statement.setString(1, getSessionIdManager().getWorkerName());
            statement.setLong(2, data.getAccessed());
            statement.setLong(3, data.getLastAccessed());
            statement.setLong(4, now);
            statement.setLong(5, data.getExpiryTime());
            statement.setString(6, data.getRowId());
            statement.executeUpdate();
            data.setLastSaved(now);
            statement.close();
            if (LOG.isDebugEnabled())
                LOG.debug("Updated access time session id="+data.getId());
        }
        finally
        {
            if (connection!=null)
                connection.close();
        }
    }




    /**
     * Delete a session from the database. Should only be called
     * when the session has been invalidated.
     *
     * @param data
     * @throws Exception
     */
    protected void deleteSession (SessionData data)
    throws Exception
    {
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_jdbcSessionIdMgr._deleteSession);
            statement.setString(1, data.getRowId());
            statement.executeUpdate();
            if (LOG.isDebugEnabled())
                LOG.debug("Deleted Session "+data);
        }
        finally
        {
            if (connection!=null)
                connection.close();
        }
    }



    /**
     * Get a connection from the driver.
     * @return
     * @throws SQLException
     */
    private Connection getConnection ()
    throws SQLException
    {
        return ((JDBCSessionIdManager)getSessionIdManager()).getConnection();
    }

    /**
     * Calculate a unique id for this session across the cluster.
     *
     * Unique id is composed of: contextpath_virtualhost0_sessionid
     * @param data
     * @return
     */
    private String calculateRowId (SessionData data)
    {
        String rowId = canonicalize(_context.getContextPath());
        rowId = rowId + "_" + getVirtualHost(_context);
        rowId = rowId+"_"+data.getId();
        return rowId;
    }

    /**
     * Get the first virtual host for the context.
     *
     * Used to help identify the exact session/contextPath.
     *
     * @return 0.0.0.0 if no virtual host is defined
     */
    private String getVirtualHost (ContextHandler.Context context)
    {
        String vhost = "0.0.0.0";

        if (context==null)
            return vhost;

        String [] vhosts = context.getContextHandler().getVirtualHosts();
        if (vhosts==null || vhosts.length==0 || vhosts[0]==null)
            return vhost;

        return vhosts[0];
    }

    /**
     * Make an acceptable file name from a context path.
     *
     * @param path
     * @return
     */
    private String canonicalize (String path)
    {
        if (path==null)
            return "";

        return path.replace('/', '_').replace('.','_').replace('\\','_');
    }
}
