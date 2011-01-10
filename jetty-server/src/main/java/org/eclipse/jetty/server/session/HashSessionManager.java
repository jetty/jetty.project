// ========================================================================
// Copyright (c) 1996-2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.SessionTrackingMode;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------ */
/** An in-memory implementation of SessionManager.
 * <p>
 * This manager supports saving sessions to disk, either periodically or at shutdown.
 * Sessions can also have their content idle saved to disk to reduce the memory overheads of large idle sessions.
 * 
 */
public class HashSessionManager extends AbstractSessionManager
{
    private static int __id;
    private Timer _timer;
    private TimerTask _task;
    private int _scavengePeriodMs=30000;
    private int _savePeriodMs=0; //don't do period saves by default
    private int _idleSavePeriodMs = 0; // don't idle save sessions by default.
    private TimerTask _saveTask;
    protected ConcurrentMap<String,HashedSession> _sessions;
    private File _storeDir;
    private boolean _lazyLoad=false;
    private volatile boolean _sessionsLoaded=false;
    
    /* ------------------------------------------------------------ */
    public HashSessionManager()
    {
        super();
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.servlet.AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        _sessions=new ConcurrentHashMap<String,HashedSession>(); 
        super.doStart();

        _timer=new Timer("HashSessionScavenger-"+__id++, true);
        
        setScavengePeriod(getScavengePeriod());

        if (_storeDir!=null)
        {
            if (!_storeDir.exists())
                _storeDir.mkdirs();

            if (!_lazyLoad)
                restoreSessions();
        }
 
        setSavePeriod(getSavePeriod());
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.servlet.AbstractSessionManager#doStop()
     */
    @Override
    public void doStop() throws Exception
    {     
        if (_storeDir != null)
            saveSessions();
        
        super.doStop();
 
        _sessions.clear();
        _sessions=null;

        // stop the scavenger
        synchronized(this)
        {
            if (_saveTask!=null)
                _saveTask.cancel();
            _saveTask=null;
            if (_task!=null)
                _task.cancel();
            _task=null;
            if (_timer!=null)
                _timer.cancel();
            _timer=null;
        }
    }

    /* ------------------------------------------------------------ */
    /** 
     * @return the period in seconds at which a check is made for sessions to be invalidated.
     */
    public int getScavengePeriod()
    {
        return _scavengePeriodMs/1000;
    }

    
    /* ------------------------------------------------------------ */
    @Override
    public Map getSessionMap()
    {
        return Collections.unmodifiableMap(_sessions);
    }


    /* ------------------------------------------------------------ */
    @Override
    public int getSessions()
    {
        int sessions=super.getSessions();
        if (Log.isDebugEnabled())
        {
            if (_sessions.size()!=sessions)
                Log.warn("sessions: "+_sessions.size()+"!="+sessions);
        }
        return sessions;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return seconds Idle period after which a session is saved 
     */
    public int getIdleSavePeriod()
    {
      if (_idleSavePeriodMs <= 0)
        return 0;

      return _idleSavePeriodMs;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Configures the period in seconds after which a session is deemed idle and saved 
     * to save on session memory.  
     * 
     * The session is persisted, the values attribute map is cleared and the session set to idled. 
     * 
     * @param seconds Idle period after which a session is saved 
     */
    public void setIdleSavePeriod(int seconds)
    {
      _idleSavePeriodMs = seconds * 1000;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxInactiveInterval(int seconds)
    {
        super.setMaxInactiveInterval(seconds);
        if (_dftMaxIdleSecs>0&&_scavengePeriodMs>_dftMaxIdleSecs*1000)
            setScavengePeriod((_dftMaxIdleSecs+9)/10);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param seconds the period is seconds at which sessions are periodically saved to disk
     */
    public void setSavePeriod (int seconds)
    {
        int period = (seconds * 1000);
        if (period < 0)
            period=0;
        _savePeriodMs=period;
        
        if (_timer!=null)
        {
            synchronized (this)
            {
                if (_saveTask!=null)
                    _saveTask.cancel();
                if (_savePeriodMs > 0 && _storeDir!=null) //only save if we have a directory configured
                {
                    _saveTask = new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                saveSessions();
                            }
                            catch (Exception e)
                            {
                                Log.warn(e);
                            }
                        }   
                    };
                    _timer.schedule(_saveTask,_savePeriodMs,_savePeriodMs);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in seconds at which sessions are periodically saved to disk
     */
    public int getSavePeriod ()
    {
        if (_savePeriodMs<=0)
            return 0;
        
        return _savePeriodMs/1000;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param seconds the period in seconds at which a check is made for sessions to be invalidated.
     */
    public void setScavengePeriod(int seconds)
    {
        if (seconds==0)
            seconds=60;

        int old_period=_scavengePeriodMs;
        int period=seconds*1000;
        if (period>60000)
            period=60000;
        if (period<1000)
            period=1000;

        _scavengePeriodMs=period;
        if (_timer!=null && (period!=old_period || _task==null))
        {
            synchronized (this)
            {
                if (_task!=null)
                    _task.cancel();
                _task = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        scavenge();
                    }   
                };
                _timer.schedule(_task,_scavengePeriodMs,_scavengePeriodMs);
            }
        }
    }
    
    /* -------------------------------------------------------------- */
    /**
     * Find sessions that have timed out and invalidate them. This runs in the
     * SessionScavenger thread.
     */
    protected void scavenge()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;
        
        Thread thread=Thread.currentThread();
        ClassLoader old_loader=thread.getContextClassLoader();
        try
        {
            if (_loader!=null)
                thread.setContextClassLoader(_loader);

            try
            {
                if (!_sessionsLoaded && _lazyLoad)
                    restoreSessions();
            }
            catch(Exception e)
            {
                Log.debug(e);
            }
            
            // For each session
            long now=System.currentTimeMillis();
            for (Iterator<HashedSession> i=_sessions.values().iterator(); i.hasNext();)
            {
                HashedSession session=i.next();
                long idleTime=session._maxIdleMs;
                if (idleTime>0&&session._accessed+idleTime<now)
                {
                    // Found a stale session, add it to the list
                    session.timeout();
                }
                else if (_idleSavePeriodMs>0&&session._accessed+_idleSavePeriodMs<now)
                {
                    session.idle(); 
                }
            }
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
                throw ((ThreadDeath)t);
            else
                Log.warn("Problem scavenging sessions", t);
        }
        finally
        {
            thread.setContextClassLoader(old_loader);
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void addSession(AbstractSessionManager.Session session)
    {
        if (isRunning())
            _sessions.put(session.getClusterId(),(HashedSession)session);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public AbstractSessionManager.Session getSession(String idInCluster)
    {
        if ( _lazyLoad && !_sessionsLoaded)
        {
            try
            {
                restoreSessions();
            }
            catch(Exception e)
            {
                Log.warn(e);
            }
        }

        Map<String,HashedSession> sessions=_sessions;
        if (sessions==null)
            return null;
        
        HashedSession session = sessions.get(idInCluster);
        
        if (session == null)
            return null;

        if (_idleSavePeriodMs!=0)
            session.deIdle();
        
        return session;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void invalidateSessions()
    {
        // Invalidate all sessions to cause unbind events
        ArrayList<HashedSession> sessions=new ArrayList<HashedSession>(_sessions.values());
        for (Iterator<HashedSession> i=sessions.iterator(); i.hasNext();)
        {
            HashedSession session=i.next();
            session.invalidate();
        }
        _sessions.clear();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected AbstractSessionManager.Session newSession(HttpServletRequest request)
    {
        return new HashedSession(request);
    }
    
    /* ------------------------------------------------------------ */
    protected AbstractSessionManager.Session newSession(long created, long accessed, String clusterId)
    {
        return new HashedSession(created,accessed, clusterId);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected boolean removeSession(String clusterId)
    {
        return _sessions.remove(clusterId)!=null;
    }
    

    /* ------------------------------------------------------------ */
    public void setStoreDirectory (File dir)
    {
        _storeDir=dir;
    }

    /* ------------------------------------------------------------ */
    public File getStoreDirectory ()
    {
        return _storeDir;
    }

    /* ------------------------------------------------------------ */
    public void setLazyLoad(boolean lazyLoad)
    {
        _lazyLoad = lazyLoad;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isLazyLoad()
    {
        return _lazyLoad;
    }

    /* ------------------------------------------------------------ */
    public void restoreSessions () throws Exception
    {
        _sessionsLoaded = true;
        
        if (_storeDir==null || !_storeDir.exists())
        {
            return;
        }

        if (!_storeDir.canRead())
        {
            Log.warn ("Unable to restore Sessions: Cannot read from Session storage directory "+_storeDir.getAbsolutePath());
            return;
        }

        File[] files = _storeDir.listFiles();
        for (int i=0;files!=null&&i<files.length;i++)
        {
            try
            {
                FileInputStream in = new FileInputStream(files[i]);           
                HashedSession session = restoreSession(in, null);
                in.close();          
                addSession(session, false);
                session.didActivate();
                files[i].delete();
            }
            catch (Exception e)
            {
                Log.warn("Problem restoring session "+files[i].getName(), e);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void saveSessions() throws Exception
    {
        if (_storeDir==null || !_storeDir.exists())
        {
            return;
        }
        
        if (!_storeDir.canWrite())
        {
            Log.warn ("Unable to save Sessions: Session persistence storage directory "+_storeDir.getAbsolutePath()+ " is not writeable");
            return;
        }

        Iterator<Map.Entry<String, HashedSession>> itor = _sessions.entrySet().iterator();
        while (itor.hasNext())
        {
            Map.Entry<String,HashedSession> entry = itor.next();
            String id = entry.getKey();
            HashedSession session = entry.getValue();
            synchronized(session)
            {
                // No point saving a session that has been idled or has had a previous save failure
                if (!session.isIdled() && !session.isSaveFailed())
                {
                    File file = null;
                    FileOutputStream fos = null;
                    try
                    {
                        file = new File (_storeDir, id);
                        if (file.exists())
                            file.delete();
                        file.createNewFile();
                        fos = new FileOutputStream (file);
                        session.willPassivate();
                        session.save(fos);
                        session.didActivate();
                        fos.close();
                    }
                    catch (Exception e)
                    {
                        session.saveFailed();

                        Log.warn("Problem persisting session "+id, e);

                        if (fos != null)
                        {
                            // Must not leave files open if the saving failed
                            IO.close(fos);
                            // No point keeping the file if we didn't save the whole session
                            file.delete();
                        }
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public HashedSession restoreSession (InputStream is, HashedSession session) throws Exception
    {
        /*
         * Take care of this class's fields first by calling 
         * defaultReadObject
         */
        DataInputStream in = new DataInputStream(is);
        String clusterId = in.readUTF();
        String nodeId = in.readUTF();
        boolean idChanged = in.readBoolean();
        long created = in.readLong();
        long cookieSet = in.readLong();
        long accessed = in.readLong();
        long lastAccessed = in.readLong();
        //boolean invalid = in.readBoolean();
        //boolean invalidate = in.readBoolean();
        //long maxIdle = in.readLong();
        //boolean isNew = in.readBoolean();
        int requests = in.readInt();
      
        if (session == null)
            session = (HashedSession)newSession(created, System.currentTimeMillis(), clusterId);
        
        session._cookieSet = cookieSet;
        session._lastAccessed = lastAccessed;
        
        int size = in.readInt();
        if (size>0)
        {
            ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(in);
            for (int i=0; i<size;i++)
            {
                String key = ois.readUTF();
                Object value = ois.readObject();
                session.setAttribute(key,value);
            }
            ois.close();
        }
        else
            in.close();
        return session;
    }

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class HashedSession extends Session
    {
        /* ------------------------------------------------------------ */
        private static final long serialVersionUID=-2134521374206116367L;
        
        /** Whether the session has been saved because it has been deemed idle; 
         * in which case its attribute map will have been saved and cleared. */
        private transient boolean _idled = false;
 
        /** Whether there has already been an attempt to save this session
         * which has failed.  If there has, there will be no more save attempts
         * for this session.  This is to stop the logs being flooded with errors
         * due to serialization failures that are most likely caused by user
         * data stored in the session that is not serializable. */
        private transient boolean _saveFailed = false;
 
        /* ------------------------------------------------------------- */
        protected HashedSession(HttpServletRequest request)
        {
            super(request);
        }

        /* ------------------------------------------------------------- */
        protected HashedSession(long created, long accessed, String clusterId)
        {
            super(created, accessed, clusterId);
        }

        /* ------------------------------------------------------------- */
        protected boolean isNotAvailable()
        {
            if (_idleSavePeriodMs!=0)
                deIdle();
            return _invalid;
        }
        
        /* ------------------------------------------------------------- */
        @Override
        public void setMaxInactiveInterval(int secs)
        {
            super.setMaxInactiveInterval(secs);
            if (_maxIdleMs>0&&(_maxIdleMs/10)<_scavengePeriodMs)
                HashSessionManager.this.setScavengePeriod((secs+9)/10);
        }

        /* ------------------------------------------------------------ */
        @Override
        public void invalidate ()
        throws IllegalStateException
        {
            if (isRunning())
            {
                super.invalidate();
                remove();
            }
        }

        /* ------------------------------------------------------------ */
        public void remove()
        {
            String id=getId();
            if (id==null)
                return;
            
            //all sessions are invalidated when jetty is stopped, make sure we don't
            //remove all the sessions in this case
            if (isStopping() || isStopped())
                return;
            
            if (_storeDir==null || !_storeDir.exists())
            {
                return;
            }
            
            File f = new File(_storeDir, id);
            f.delete();
        }

        /* ------------------------------------------------------------ */
        public synchronized void save(OutputStream os)  throws IOException 
        {
            DataOutputStream out = new DataOutputStream(os);
            out.writeUTF(_clusterId);
            out.writeUTF(_nodeId);
            out.writeBoolean(_idChanged);
            out.writeLong( _created);
            out.writeLong(_cookieSet);
            out.writeLong(_accessed);
            out.writeLong(_lastAccessed);
            
            /* Don't write these out, as they don't make sense to store because they
             * either they cannot be true or their value will be restored in the 
             * Session constructor.
             */
            //out.writeBoolean(_invalid);
            //out.writeBoolean(_doInvalidate);
            //out.writeLong(_maxIdleMs);
            //out.writeBoolean( _newSession);
            out.writeInt(_requests);
            if (_attributes != null)
            {
                out.writeInt(_attributes.size());
                ObjectOutputStream oos = new ObjectOutputStream(out);
                for (Map.Entry<String,Object> entry: _attributes.entrySet())
                {
                    oos.writeUTF(entry.getKey());
                    oos.writeObject(entry.getValue());
                }
                oos.close();
            }
            else
            {
                out.writeInt(0);
                out.close();
            }
        }

        /* ------------------------------------------------------------ */
        public synchronized void deIdle()
        {
            if (isIdled())
            {
                // Access now to prevent race with idling period
                access(System.currentTimeMillis());
                
                if (Log.isDebugEnabled())
                {
                    Log.debug("Deidling " + super.getId());
                }

                FileInputStream fis = null;

                try
                {
                    File file = new File(_storeDir, super.getId());
                    if (!file.exists() || !file.canRead())
                        throw new FileNotFoundException(file.getName());

                    fis = new FileInputStream(file);
                    restoreSession(fis, this);

                    _idled = false;
                    didActivate();
                    
                    // If we are doing period saves, then there is no point deleting at this point 
                    if (_savePeriodMs == 0)
                        file.delete();
                }
                catch (Exception e)
                {
                    Log.warn("Problem deidling session " + super.getId(), e);
                    IO.close(fis);
                    invalidate();
                }
            }
        }

        /* ------------------------------------------------------------ */
        /**
         * Idle the session to reduce session memory footprint.
         * 
         * The session is idled by persisting it, then clearing the session values attribute map and finally setting 
         * it to an idled state.  
         */
        public synchronized void idle()
        {
            // Only idle the session if not already idled and no previous save/idle has failed
            if (!isIdled() && !_saveFailed)
            {
                if (Log.isDebugEnabled())
                    Log.debug("Idling " + super.getId());

                File file = null;
                FileOutputStream fos = null;
                
                try
                {
                    file = new File(_storeDir, super.getId());

                    if (file.exists())
                        file.delete();
                    file.createNewFile();
                    fos = new FileOutputStream(file);
                    willPassivate();
                    save(fos);

                    _attributes.clear();

                    _idled = true;
                }
                catch (Exception e)
                {
                    saveFailed(); // We won't try again for this session

                    Log.warn("Problem idling session " + super.getId(), e);

                    if (fos != null)
                    {
                        // Must not leave the file open if the saving failed
                        IO.close(fos);
                        // No point keeping the file if we didn't save the whole session
                        file.delete();
                        _idled=false; // assume problem was before _values.clear();
                    }
                }
            }
        }
        
        /* ------------------------------------------------------------ */
        public boolean isIdled()
        {
          return _idled;
        }

        /* ------------------------------------------------------------ */
        public boolean isSaveFailed()
        {
          return _saveFailed;
        }
        
        /* ------------------------------------------------------------ */
        public void saveFailed()
        {
          _saveFailed = true;
        }

    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class ClassLoadingObjectInputStream extends ObjectInputStream
    {
        /* ------------------------------------------------------------ */
        public ClassLoadingObjectInputStream(java.io.InputStream in) throws IOException
        {
            super(in);
        }

        /* ------------------------------------------------------------ */
        public ClassLoadingObjectInputStream () throws IOException
        {
            super();
        }

        /* ------------------------------------------------------------ */
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
}
