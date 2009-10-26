// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;

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
 * accessTime (time in ms session was accessed)
 * lastAccessTime (previous time in ms session was accessed)
 * createTime (time in ms session created)
 * cookieTime (time in ms session cookie created)
 * lastSavedTime (last time in ms session access times were saved)
 * expiryTime (time in ms that the session is due to expire)
 * map (attribute map)
 * 
 * As an optimisation, to prevent thrashing the database, we do not persist
 * the accessTime and lastAccessTime every time the session is accessed. Rather,
 * we write it out every so often. The frequency is controlled by the saveIntervalSec
 * field.
 */
public class JDBCSessionManager extends AbstractSessionManager
{  
    protected  String __insertSession;  
    protected  String __deleteSession; 
    protected  String __selectSession;   
    protected  String __updateSession;  
    protected  String __updateSessionNode; 
    protected  String __updateSessionAccessTime;
    
    private ConcurrentHashMap _sessions;
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
        private long _maxIdleMs;
        private long _cookieSet;
        private long _created;
        private Map _attributes;
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
            _attributes = new ConcurrentHashMap();
            _lastNode = getIdManager().getWorkerName();
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
        
        protected synchronized Map getAttributeMap ()
        {
            return _attributes;
        }
        
        protected synchronized void setAttributeMap (ConcurrentHashMap map)
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
    public class Session extends AbstractSessionManager.Session
    {
        private final SessionData _data;
        private boolean _dirty=false;

        /**
         * Session from a request.
         * 
         * @param request
         */
        protected Session (HttpServletRequest request)
        {
         
            super(request);   
            _data = new SessionData(_clusterId);
            _data.setMaxIdleMs(_dftMaxIdleSecs*1000);
            _data.setCanonicalContext(canonicalize(_context.getContextPath()));
            _data.setVirtualHost(getVirtualHost(_context));
            _data.setExpiryTime(_maxIdleMs < 0 ? 0 : (System.currentTimeMillis() + _maxIdleMs));
            _values=_data.getAttributeMap();
        }

        /**
          * Session restored in database.
          * @param row
          */
         protected Session (SessionData data)
         {
             super(data.getCreated(), data.getId());
             _data=data;
             _values=data.getAttributeMap();
         }
        
         @Override
        protected Map newAttributeMap()
         {
             return _data.getAttributeMap();
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
         * @see org.eclipse.jetty.server.session.AbstractSessionManager.Session#access(long)
         */
        @Override
        protected void access(long time)
        {
            super.access(time);
            _data.setLastAccessed(_data.getAccessed());
            _data.setAccessed(time);
            _data.setExpiryTime(_maxIdleMs < 0 ? 0 : (time + _maxIdleMs));
        }

        /** 
         * Exit from session
         * @see org.eclipse.jetty.server.session.AbstractSessionManager.Session#complete()
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
                else if ((_data._accessed - _data._lastSaved) >= (getSaveInterval() * 1000))
                    updateSessionAccessTime(_data);
                
            }
            catch (Exception e)
            {
                Log.warn("Problem persisting changed session data id="+getId(), e);
            }
            finally
            {
                _dirty=false;
            }
        }
        
        @Override
        protected void timeout() throws IllegalStateException
        {
            if (Log.isDebugEnabled()) Log.debug("Timing out session id="+getClusterId());
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
        public Class resolveClass (java.io.ObjectStreamClass cl) throws IOException, ClassNotFoundException
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
                //check if we need to reload the session - don't do it on every call
                //to reduce the load on the database. This introduces a window of 
                //possibility that the node may decide that the session is local to it,
                //when the session has actually been live on another node, and then
                //re-migrated to this node. This should be an extremely rare occurrence,
                //as load-balancers are generally well-behaved and consistently send 
                //sessions to the same node, changing only iff that node fails.
                SessionData data = null;
                long now = System.currentTimeMillis();
                if (Log.isDebugEnabled()) Log.debug("now="+now+
                        " lastSaved="+(session==null?0:session._data._lastSaved)+
                        " interval="+(_saveIntervalSec * 1000)+
                        " difference="+(now - (session==null?0:session._data._lastSaved)));
                if (session==null || ((now - session._data._lastSaved) >= (_saveIntervalSec * 1000)))
                {
                    data = loadSession(idInCluster, canonicalize(_context.getContextPath()), getVirtualHost(_context));
                }
                else
                    data = session._data;
                
                if (data != null)
                {
                    if (!data.getLastNode().equals(getIdManager().getWorkerName()) || session==null)
                    {
                        //session last used on a different node, or we don't have it in memory
                        session = new Session(data);
                        _sessions.put(idInCluster, session);
                        session.didActivate();
                        //TODO is this the best way to do this? Or do this on the way out using
                        //the _dirty flag?
                        updateSessionNode(data);
                    }
                    else
                        if (Log.isDebugEnabled()) Log.debug("Session not stale "+session._data);
                    //session in db shares same id, but is not for this context
                }
                else
                {
                    //No session in db with matching id and context path.
                    session=null;
                    if (Log.isDebugEnabled()) Log.debug("No session in database matching id="+idInCluster);
                }
                
                return session;
            }
            catch (Exception e)
            {
                Log.warn("Unable to load session from database", e);
                return null;
            }
        }
    }

   
    /** 
     * Get all the sessions as a map of id to Session.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#getSessionMap()
     */
    @Override
    public Map getSessionMap()
    {
       return Collections.unmodifiableMap(_sessions);
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
        
        prepareTables();
     
        _sessions = new ConcurrentHashMap();
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
        synchronized (this)
        {
            Session session = (Session)_sessions.get(idInCluster);
            if (session != null)
            {
                session.invalidate();
            }
        }
    }
   
    /** 
     * Delete an existing session, both from the in-memory map and
     * the database.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#removeSession(java.lang.String)
     */
    @Override
    protected void removeSession(String idInCluster)
    {
        synchronized (this)
        {
           try
           {
               Session session = (Session)_sessions.remove(idInCluster);
               deleteSession(session._data);
           }
           catch (Exception e)
           {
               Log.warn("Problem deleting session id="+idInCluster, e);
           }
        }
    }


    /** 
     * Add a newly created session to our in-memory list for this node and persist it.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#addSession(org.eclipse.jetty.server.session.AbstractSessionManager.Session)
     */
    @Override
    protected void addSession(AbstractSessionManager.Session session)
    {
        if (session==null)
            return;
        
        synchronized (this)
        {
            _sessions.put(session.getClusterId(), session);
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
                Log.warn("Unable to store new session id="+session.getId() , e);
            }
        }
    }


    /** 
     * Make a new Session.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#newSession(javax.servlet.http.HttpServletRequest)
     */
    @Override
    protected AbstractSessionManager.Session newSession(HttpServletRequest request)
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
    public void removeSession(AbstractSessionManager.Session session, boolean invalidate)
    {
        // Remove session from context and global maps
        synchronized (_sessionIdManager)
        {
            boolean removed = false;
            
            synchronized (this)
            {
                //take this session out of the map of sessions for this context
                if (_sessions.get(session.getClusterId()) != null)
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
            }
        }
        
        if (invalidate && _sessionListeners!=null)
        {
            HttpSessionEvent event=new HttpSessionEvent(session);
            for (int i=LazyList.size(_sessionListeners); i-->0;)
                ((HttpSessionListener)LazyList.get(_sessionListeners,i)).sessionDestroyed(event);
        }
        if (!invalidate)
        {
            session.willPassivate();
        }
    }
    
    
    /**
     * Expire any Sessions we have in memory matching the list of
     * expired Session ids.
     * 
     * @param sessionIds
     */
    protected void expire (List sessionIds)
    { 
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        //Remove any sessions we already have in memory that match the ids
        Thread thread=Thread.currentThread();
        ClassLoader old_loader=thread.getContextClassLoader();
        ListIterator itor = sessionIds.listIterator();

        try
        {
            while (itor.hasNext())
            {
                String sessionId = (String)itor.next();
                if (Log.isDebugEnabled()) Log.debug("Expiring session id "+sessionId);
                Session session = (Session)_sessions.get(sessionId);
                if (session != null)
                {
                    session.timeout();
                    itor.remove();
                    int count = this._sessions.size();
                    if (count < this._minSessions)
                        this._minSessions=count;
                }
                else
                {
                    if (Log.isDebugEnabled()) Log.debug("Unrecognized session id="+sessionId);
                }
            }
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
                throw ((ThreadDeath)t);
            else
                Log.warn("Problem expiring sessions", t);
        }
        finally
        {
            thread.setContextClassLoader(old_loader);
        }
    }
    
 
    protected void prepareTables ()
    {
        __insertSession = "insert into "+((JDBCSessionIdManager)_sessionIdManager)._sessionTable+
                          " (rowId, sessionId, contextPath, virtualHost, lastNode, accessTime, lastAccessTime, createTime, cookieTime, lastSavedTime, expiryTime, map) "+
                          " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        __deleteSession = "delete from "+((JDBCSessionIdManager)_sessionIdManager)._sessionTable+
                          " where rowId = ?";

        __selectSession = "select * from "+((JDBCSessionIdManager)_sessionIdManager)._sessionTable+
                          " where sessionId = ? and contextPath = ? and virtualHost = ?";

        __updateSession = "update "+((JDBCSessionIdManager)_sessionIdManager)._sessionTable+
                          " set lastNode = ?, accessTime = ?, lastAccessTime = ?, lastSavedTime = ?, expiryTime = ?, map = ? where rowId = ?";

        __updateSessionNode = "update "+((JDBCSessionIdManager)_sessionIdManager)._sessionTable+
                              " set lastNode = ? where rowId = ?";

        __updateSessionAccessTime = "update "+((JDBCSessionIdManager)_sessionIdManager)._sessionTable+
                                    " set lastNode = ?, accessTime = ?, lastAccessTime = ?, lastSavedTime = ?, expiryTime = ? where rowId = ?";
    }
    
    /**
     * Load a session from the database
     * @param id
     * @return
     * @throws Exception
     */
    protected SessionData loadSession (String id, String canonicalContextPath, String vhost)
    throws Exception
    {
        SessionData data = null;
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {
            statement = connection.prepareStatement(__selectSession);
            statement.setString(1, id);
            statement.setString(2, canonicalContextPath);
            statement.setString(3, vhost);
            ResultSet result = statement.executeQuery();
            if (result.next())
            {
               data = new SessionData(id);
               data.setRowId(result.getString("rowId"));
               data.setCookieSet(result.getLong("cookieTime"));
               data.setLastAccessed(result.getLong("lastAccessTime"));
               data.setAccessed (result.getLong("accessTime"));
               data.setCreated(result.getLong("createTime"));
               data.setLastNode(result.getString("lastNode"));
               data.setLastSaved(result.getLong("lastSavedTime"));
               data.setExpiryTime(result.getLong("expiryTime"));
               data.setCanonicalContext(result.getString("contextPath"));
               data.setVirtualHost(result.getString("virtualHost"));

               InputStream is = ((JDBCSessionIdManager)getIdManager())._dbAdaptor.getBlobInputStream(result, "map");
               ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream (is);
               Object o = ois.readObject();
               data.setAttributeMap((ConcurrentHashMap)o);
               ois.close();
               
               if (Log.isDebugEnabled())
                   Log.debug("LOADED session "+data);
            }
            return data;
        }   
        finally
        {
            if (connection!=null)
                connection.close();
        }
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
            statement = connection.prepareStatement(__insertSession);
            statement.setString(1, rowId); //rowId
            statement.setString(2, data.getId()); //session id
            statement.setString(3, data.getCanonicalContext()); //context path
            statement.setString(4, data.getVirtualHost()); //first vhost
            statement.setString(5, getIdManager().getWorkerName());//my node id
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

            
            if (Log.isDebugEnabled())
                Log.debug("Stored session "+data);
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
            statement = connection.prepareStatement(__updateSession);     
            statement.setString(1, getIdManager().getWorkerName());//my node id
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
            if (Log.isDebugEnabled())
                Log.debug("Updated session "+data);
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
        String nodeId = getIdManager().getWorkerName();
        Connection connection = getConnection();
        PreparedStatement statement = null;
        try
        {            
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(__updateSessionNode);
            statement.setString(1, nodeId);
            statement.setString(2, data.getRowId());
            statement.executeUpdate();
            statement.close();
            if (Log.isDebugEnabled())
                Log.debug("Updated last node for session id="+data.getId()+", lastNode = "+nodeId);
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
            statement = connection.prepareStatement(__updateSessionAccessTime);
            statement.setString(1, getIdManager().getWorkerName());
            statement.setLong(2, data.getAccessed());
            statement.setLong(3, data.getLastAccessed());
            statement.setLong(4, now);
            statement.setLong(5, data.getExpiryTime());
            statement.setString(6, data.getRowId());
            statement.executeUpdate();
            data.setLastSaved(now);
            statement.close();
            if (Log.isDebugEnabled())
                Log.debug("Updated access time session id="+data.getId());
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
            statement = connection.prepareStatement(__deleteSession);
            statement.setString(1, data.getRowId());
            statement.executeUpdate();
            if (Log.isDebugEnabled())
                Log.debug("Deleted Session "+data);
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
        return ((JDBCSessionIdManager)getIdManager()).getConnection();
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
