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
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");

    private final NoSqlSessionManager _manager;
    private Set<String> _dirty;
    private final AtomicInteger _active = new AtomicInteger();
    private Object _version;
    private long _lastSync;

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
    
    

    @Override
    protected void timeout() throws IllegalStateException
    {
        super.timeout();
    }


    
    /* ------------------------------------------------------------ */
    @Override
    protected void checkValid() throws IllegalStateException
    {
        super.checkValid();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected boolean access(long time)
    {
        __log.debug("NoSqlSession:access:active {} time {}", _active, time);
        if (_active.incrementAndGet()==1)
        {
            long period=_manager.getStalePeriod()*1000L;
            if (period==0)
                refresh();
            else if (period>0)
            {
                long stale=time-_lastSync;
                __log.debug("NoSqlSession:access:stale "+stale);
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

    @Override
    public void setClusterId(String clusterId)
    {
        super.setClusterId(clusterId);
    }

    @Override
    public void setNodeId(String nodeId)
    {
        super.setNodeId(nodeId);
    }
}
