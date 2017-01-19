//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.nosql;


import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.session.MemSession;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
public class NoSqlSession extends MemSession
{
    private final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    private enum IdleState {NOT_IDLE, IDLE, IDLING, DEIDLING};

    private final NoSqlSessionManager _manager;
    private Set<String> _dirty;
    private final AtomicInteger _active = new AtomicInteger();
    private Object _version;
    private long _lastSync;

    private IdleState _idle = IdleState.NOT_IDLE;
    
    private boolean _deIdleFailed;

    /* ------------------------------------------------------------ */
    public NoSqlSession(NoSqlSessionManager manager, HttpServletRequest request)
    {
        super(manager, request);
        _manager=manager;
        _active.incrementAndGet();
    }
    
    /* ------------------------------------------------------------ */
    public NoSqlSession(NoSqlSessionManager manager, long created, long accessed, String clusterId, Object version)
    {
        super(manager, created,accessed,clusterId);
        _manager=manager;
        _version=version;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Object doPutOrRemove(String name, Object value)
    {
        synchronized (this)
        {
            Object old = super.doPutOrRemove(name,value);
            
            if (_manager.getSavePeriod()==-2)
            {
                save(true);
            }
            return old;
        }
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object old = changeAttribute(name,value);
        if (value == null && old == null)
            return; //not dirty, no change
        
        if (value==null || !value.equals(old))
        {
            if (_dirty==null)
            {
                _dirty=new HashSet<String>();
            }
            
            _dirty.add(name);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void timeout() throws IllegalStateException
    {
        super.timeout();
    }


    
    /* ------------------------------------------------------------ */
    @Override
    protected void checkValid() throws IllegalStateException
    {
        //whenever a method is called on the session, check that it was not idled and
        //reinflate it if necessary
        if (!isDeIdleFailed() && _manager.getIdlePeriod() > 0 && isIdle())
            deIdle();
        try
        {
            super.checkValid();
        }
        catch (IllegalStateException e)
        {
            throw new IllegalStateException (e.getMessage()+" idle="+_idle+" deidleFailed="+_deIdleFailed+" version="+_version, e);
        }
    }
    
    

    /* ------------------------------------------------------------ */
    @Override
    protected boolean access(long time)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("NoSqlSession:access:active {} time {}", _active, time);
        if (_active.incrementAndGet()==1)
        {
            long period=_manager.getStalePeriod()*1000L;
            if (period==0)
                refresh();
            else if (period>0)
            {
                long stale=time-_lastSync;
                if (LOG.isDebugEnabled())
                    LOG.debug("NoSqlSession:access:stale "+stale);
                if (stale>period)
                    refresh();
            }
        }

        return super.access(time);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void complete()
    {
        super.complete();
        if(_active.decrementAndGet()==0)
        {
            switch(_manager.getSavePeriod())
            {
                case 0: 
                    save(isValid());
                    break;
                case 1:
                    if (isDirty())
                        save(isValid());
                    break;

            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doInvalidate() throws IllegalStateException
    {
        super.doInvalidate();
        //jb why save here? if the session is invalidated it should be removed
        save(false);
    }
    
    /* ------------------------------------------------------------ */
    protected void save(boolean activateAfterSave)
    {
        synchronized (this)
        {
            _version=_manager.save(this,_version,activateAfterSave);
            _lastSync=getAccessed();
        }
    }
    
    
    /* ------------------------------------------------------------ */
    public void idle ()
    {
        synchronized (this)
        {
            if (!isIdle() && !isIdling()) //don't re-idle an idle session as the attribute map will be empty
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Idling {}", super.getId());
                setIdling();
                save(false);
                willPassivate();
                clearAttributes();
                setIdle(true);
            }
        }
    }
    
    
    /* ------------------------------------------------------------ */
    public synchronized void deIdle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Checking before de-idling {}, isidle:{}, isDeidleFailed:", super.getId(), isIdle(), isDeIdleFailed());
        
        if (isIdle() && !isDeIdleFailed())
        {

            setDeIdling();
            if (LOG.isDebugEnabled())
                LOG.debug("De-idling " + super.getId());

            // Update access time to prevent race with idling period
            super.access(System.currentTimeMillis());

            //access may have expired and invalidated the session, so only deidle if it is still valid
            if (isValid())
            {
                try
                {    
                    setIdle(false);
                    _version=_manager.refresh(this, new Long(0)); //ensure version should not match to force refresh
                    if (_version == null)
                        setDeIdleFailed(true);
                }
                catch (Exception e)
                {
                    setDeIdleFailed(true);
                    LOG.warn("Problem de-idling session " + super.getId(), e);
                    invalidate();
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    public synchronized boolean isIdle ()
    {
        return _idle == IdleState.IDLE;
    }
    
    
    /* ------------------------------------------------------------ */
    public synchronized boolean isIdling ()
    {
        return _idle == IdleState.IDLING;
    }
    
    /* ------------------------------------------------------------ */
    public synchronized boolean isDeIdling()
    {
        return _idle == IdleState.DEIDLING;
    }
    
    
    public synchronized void setIdling ()
    {
        _idle = IdleState.IDLING;
    }
    
    public synchronized void setDeIdling ()
    {
        _idle = IdleState.DEIDLING;
    }
    
    /* ------------------------------------------------------------ */
    public synchronized void setIdle (boolean idle)
    {
        if (idle)
            _idle = IdleState.IDLE;
        else
            _idle = IdleState.NOT_IDLE;
    }
    

    public boolean isDeIdleFailed()
    {
        return _deIdleFailed;
    }

    public void setDeIdleFailed(boolean _deIdleFailed)
    {
        this._deIdleFailed = _deIdleFailed;
    }

    /* ------------------------------------------------------------ */
    protected void refresh()
    {
        synchronized (this)
        {
            _version=_manager.refresh(this,_version);
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isDirty()
    {
        synchronized (this)
        {
            return _dirty!=null && !_dirty.isEmpty();
        }
    }
    
    /* ------------------------------------------------------------ */
    public Set<String> takeDirty()
    {
        synchronized (this)
        {
            Set<String> dirty=_dirty;
            if (dirty==null)
                dirty= new HashSet<String>();
            else
                _dirty=null;
            return dirty;
        }
    }

    /* ------------------------------------------------------------ */
    public Object getVersion()
    {
        return _version;
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    public void setClusterId(String clusterId)
    {
        super.setClusterId(clusterId);
    }

    
    /* ------------------------------------------------------------ */
    @Override
    public void setNodeId(String nodeId)
    {
        super.setNodeId(nodeId);
    }
}
