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
     * Session
     *
     * Session instance.
     */
    public class Session extends AbstractSession
    {
        private static final long serialVersionUID = 5208464051134226143L;
        
        /**
         * If dirty, session needs to be (re)persisted
         */
        private boolean _dirty=false;
        
        
        /**
         * Time in msec since the epoch that a session cookie was set for this session
         */
        private long _cookieSet;
        
        
        /**
         * Time in msec since the epoch that the session will expire
         */
        private long _expiryTime;
        
        
        /**
         * Time in msec since the epoch that the session was last persisted
         */
        private long _lastSaved;
        
        
        /**
         * Unique identifier of the last node to host the session
         */
        private String _lastNode;
        
        
        /**
         * Virtual host for context (used to help distinguish 2 sessions with same id on different contexts)
         */
        private String _virtualHost;
        
        
        /**
         * Unique row in db for session
         */
        private String _rowId;
        
        
        /**
         * Mangled context name (used to help distinguish 2 sessions with same id on different contexts)
         */
        private String _canonicalContext;
        
   
        /**
         * Session from a request.
         *
         * @param request
         */
        protected Session (HttpServletRequest request)
        {
            super(JDBCSessionManager.this,request);
            int maxInterval=getMaxInactiveInterval();
            _expiryTime = (maxInterval <= 0 ? 0 : (System.currentTimeMillis() + maxInterval*1000L));
            _virtualHost = JDBCSessionManager.getVirtualHost(_context);
            _canonicalContext = canonicalize(_context.getContextPath());
            _lastNode = getSessionIdManager().getWorkerName();
        }
        
        
        /**
         * Session restored from database
         * @param sessionId
         * @param rowId
         * @param created
         * @param accessed
         */
        protected Session (String sessionId, String rowId, long created, long accessed)
        {
            super(JDBCSessionManager.this, created, accessed, sessionId);
            _rowId = rowId;
        }
        
        
        protected synchronized String getRowId()
        {
            return _rowId;
        }
        
        protected synchronized void setRowId(String rowId)
        {
            _rowId = rowId;
        }
        
        public synchronized void setVirtualHost (String vhost)
        {
            _virtualHost=vhost;
        }

        public synchronized String getVirtualHost ()
        {
            return _virtualHost;
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
        

        public synchronized void setCanonicalContext(String str)
        {
            _canonicalContext=str;
        }

        public synchronized String getCanonicalContext ()
        {
            return _canonicalContext;
        }
        
        public void setCookieSet (long ms)
        {
            _cookieSet = ms;
        }

        public synchronized long getCookieSet ()
        {
            return _cookieSet;
        }

        public synchronized void setLastNode (String node)
        {
            _lastNode=node;
        }

        public synchronized String getLastNode ()
        {
            return _lastNode;
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
            _cookieSet = getAccessed();
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
            synchronized (this)
            {
                if (super.access(time))
                {
                    int maxInterval=getMaxInactiveInterval();
                    _expiryTime = (maxInterval <= 0 ? 0 : (time + maxInterval*1000L));
                    return true;
                }
                return false;
            }
        }
        


        /**
         * Exit from session
         * @see org.eclipse.jetty.server.session.AbstractSession#complete()
         */
        @Override
        protected void complete()
        {
            synchronized (this)
            {
                super.complete();
                try
                {
                    if (isValid())
                    {
                        if (_dirty)
                        {
                            //The session attributes have changed, write to the db, ensuring
                            //http passivation/activation listeners called
                            willPassivate();                      
                            updateSession(this);
                            didActivate();
                        }
                        else if ((getAccessed() - _lastSaved) >= (getSaveInterval() * 1000L))
                        {
                            updateSessionAccessTime(this);
                        }
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
        }

        @Override
        protected void timeout() throws IllegalStateException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Timing out session id="+getClusterId());
            super.timeout();
        }
        
        @Override
        public String toString ()
        {
            return "Session rowId="+_rowId+",id="+getId()+",lastNode="+_lastNode+
                            ",created="+getCreationTime()+",accessed="+getAccessed()+
                            ",lastAccessed="+getLastAccessedTime()+",cookieSet="+_cookieSet+
                            ",lastSaved="+_lastSaved+",expiry="+_expiryTime;
        }
    }




    /**
     * ClassLoadingObjectInputStream
     *
     * Used to persist the session attribute map
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
     * A session has been requested by its id on this node.
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
        Session session = null;
        Session memSession = (Session)_sessions.get(idInCluster);

        synchronized (this)
        {
                //check if we need to reload the session -
                //as an optimization, don't reload on every access
                //to reduce the load on the database. This introduces a window of
                //possibility that the node may decide that the session is local to it,
                //when the session has actually been live on another node, and then
                //re-migrated to this node. This should be an extremely rare occurrence,
                //as load-balancers are generally well-behaved and consistently send
                //sessions to the same node, changing only iff that node fails.
                //Session data = null;
                long now = System.currentTimeMillis();
                if (LOG.isDebugEnabled())
                {
                    if (memSession==null)
                        LOG.debug("getSession("+idInCluster+"): not in session map,"+
                                " now="+now+
                                " lastSaved="+(memSession==null?0:memSession._lastSaved)+
                                " interval="+(_saveIntervalSec * 1000L));
                    else
                        LOG.debug("getSession("+idInCluster+"): in session map, "+
                                " now="+now+
                                " lastSaved="+(memSession==null?0:memSession._lastSaved)+
                                " interval="+(_saveIntervalSec * 1000L)+
                                " lastNode="+memSession._lastNode+
                                " thisNode="+getSessionIdManager().getWorkerName()+
                                " difference="+(now - memSession._lastSaved));
                }

                try
                {
                    if (memSession==null)
                    {
                        LOG.debug("getSession("+idInCluster+"): no session in session map. Reloading session data from db.");
                        session = loadSession(idInCluster, canonicalize(_context.getContextPath()), getVirtualHost(_context));
                    }
                    else if ((now - memSession._lastSaved) >= (_saveIntervalSec * 1000L))
                    {
                        LOG.debug("getSession("+idInCluster+"): stale session. Reloading session data from db.");
                        session = loadSession(idInCluster, canonicalize(_context.getContextPath()), getVirtualHost(_context));
                    }
                    else
                    {
                        LOG.debug("getSession("+idInCluster+"): session in session map");
                        session = memSession;
                    }
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to load session "+idInCluster, e);
                    return null;
                }

                
                //If we have a session
                if (session != null)
                {
                    //If the session was last used on a different node, or session doesn't exist on this node
                    if (!session.getLastNode().equals(getSessionIdManager().getWorkerName()) || memSession==null)
                    {
                        //if session doesn't expire, or has not already expired, update it and put it in this nodes' memory
                        if (session._expiryTime <= 0 || session._expiryTime > now)
                        {
                            if (LOG.isDebugEnabled()) 
                                LOG.debug("getSession("+idInCluster+"): lastNode="+session.getLastNode()+" thisNode="+getSessionIdManager().getWorkerName());
                            
                            session.setLastNode(getSessionIdManager().getWorkerName());                            
                            _sessions.put(idInCluster, session);
                            
                            //update in db: if unable to update, session will be scavenged later
                            try
                            {
                                updateSessionNode(session);
                                session.didActivate();
                            }
                            catch (Exception e)
                            {
                                LOG.warn("Unable to update freshly loaded session "+idInCluster, e);
                                return null;
                            }
                        }
                        else
                        {
                            LOG.debug("getSession ({}): Session has expired", idInCluster);  
                            session=null;
                        }

                    }
                    else
                    {
                       //the session loaded from the db and the one in memory are the same, so keep using the one in memory
                       session = memSession;
                       LOG.debug("getSession({}): Session not stale {}", idInCluster,session);
                    }
                }
                else
                {
                    //No session in db with matching id and context path.
                    LOG.debug("getSession({}): No session in database matching id={}",idInCluster,idInCluster);
                }

                return session;
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
                    deleteSession(session);
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
            synchronized (session)
            {
                session.willPassivate();
                storeSession(((JDBCSessionManager.Session)session));
                session.didActivate();
            }
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
    protected Session loadSession (final String id, final String canonicalContextPath, final String vhost)
    throws Exception
    {
        final AtomicReference<Session> _reference = new AtomicReference<Session>();
        final AtomicReference<Exception> _exception = new AtomicReference<Exception>();
        Runnable load = new Runnable()
        {
            @SuppressWarnings("unchecked")
            public void run()
            {
                Session session = null;
                Connection connection=null;
                PreparedStatement statement = null;
                try
                {
                    connection = getConnection();
                    statement = _jdbcSessionIdMgr._dbAdaptor.getLoadStatement(connection, id, canonicalContextPath, vhost);
                    ResultSet result = statement.executeQuery();
                    if (result.next())
                    {                    
                        session = new Session(id, result.getString(_jdbcSessionIdMgr._sessionTableRowId), result.getLong("createTime"), result.getLong("accessTime"));
                        session.setCookieSet(result.getLong("cookieTime"));
                        session.setLastAccessedTime(result.getLong("lastAccessTime"));
                        session.setLastNode(result.getString("lastNode"));
                        session.setLastSaved(result.getLong("lastSavedTime"));
                        session.setExpiryTime(result.getLong("expiryTime"));
                        session.setCanonicalContext(result.getString("contextPath"));
                        session.setVirtualHost(result.getString("virtualHost"));
                                           
                        InputStream is = ((JDBCSessionIdManager)getSessionIdManager())._dbAdaptor.getBlobInputStream(result, "map");
                        ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream (is);
                        Object o = ois.readObject();
                        session.addAttributes((Map<String,Object>)o);
                        ois.close();

                        if (LOG.isDebugEnabled())
                            LOG.debug("LOADED session "+session);
                    }
                    _reference.set(session);
                }
                catch (Exception e)
                {
                    _exception.set(e);
                }
                finally
                {
                    if (statement!=null)
                    {
                        try { statement.close(); }
                        catch(Exception e) { LOG.warn(e); }
                    }

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
        {
            //if the session could not be restored, take its id out of the pool of currently-in-use
            //session ids
            _jdbcSessionIdMgr.removeSession(id);
            throw _exception.get();
        }

        return _reference.get();
    }

    /**
     * Insert a session into the database.
     *
     * @param data
     * @throws Exception
     */
    protected void storeSession (Session session)
    throws Exception
    {
        if (session==null)
            return;

        //put into the database
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            String rowId = calculateRowId(session);

            long now = System.currentTimeMillis();
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_jdbcSessionIdMgr._insertSession);
            statement.setString(1, rowId); //rowId
            statement.setString(2, session.getId()); //session id
            statement.setString(3, session.getCanonicalContext()); //context path
            statement.setString(4, session.getVirtualHost()); //first vhost
            statement.setString(5, getSessionIdManager().getWorkerName());//my node id
            statement.setLong(6, session.getAccessed());//accessTime
            statement.setLong(7, session.getLastAccessedTime()); //lastAccessTime
            statement.setLong(8, session.getCreationTime()); //time created
            statement.setLong(9, session.getCookieSet());//time cookie was set
            statement.setLong(10, now); //last saved time
            statement.setLong(11, session.getExpiryTime());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(session.getAttributeMap());
            byte[] bytes = baos.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            statement.setBinaryStream(12, bais, bytes.length);//attribute map as blob

            statement.executeUpdate();
            session.setRowId(rowId); //set it on the in-memory data as well as in db
            session.setLastSaved(now);


            if (LOG.isDebugEnabled())
                LOG.debug("Stored session "+session);
        }
        finally
        {
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            if (connection!=null)
                connection.close();
        }
    }


    /**
     * Update data on an existing persisted session.
     *
     * @param data the session
     * @throws Exception
     */
    protected void updateSession (Session data)
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
            statement.setLong(3, data.getLastAccessedTime()); //lastAccessTime
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
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            if (connection!=null)
                connection.close();
        }
    }


    /**
     * Update the node on which the session was last seen to be my node.
     *
     * @param data the session
     * @throws Exception
     */
    protected void updateSessionNode (Session data)
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
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            if (connection!=null)
                connection.close();
        }
    }

    /**
     * Persist the time the session was last accessed.
     *
     * @param data the session
     * @throws Exception
     */
    private void updateSessionAccessTime (Session data)
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
            statement.setLong(3, data.getLastAccessedTime());
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
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

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
    protected void deleteSession (Session data)
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
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

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
    private String calculateRowId (Session data)
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
    private static String getVirtualHost (ContextHandler.Context context)
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
    private static String canonicalize (String path)
    {
        if (path==null)
            return "";

        return path.replace('/', '_').replace('.','_').replace('\\','_');
    }
}
