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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;


/**
 * MemSession
 *
 * A session whose data is kept in memory
 */
public class MemSession extends AbstractSession
{

    private final Map<String,Object> _attributes=new HashMap<String, Object>();

    protected MemSession(AbstractSessionManager abstractSessionManager, HttpServletRequest request)
    {
        super(abstractSessionManager, request);
    }

    public MemSession(AbstractSessionManager abstractSessionManager, long created, long accessed, String clusterId)
    {
        super(abstractSessionManager, created, accessed, clusterId);
    }
    
    
    /* ------------------------------------------------------------- */
    @Override
    public Map<String,Object> getAttributeMap()
    {
        return _attributes;
    }
   

    /* ------------------------------------------------------------ */
    @Override
    public int getAttributes()
    {
        synchronized (this)
        {
            checkValid();
            return _attributes.size();
        }
    }

    /* ------------------------------------------------------------ */
    @SuppressWarnings({ "unchecked" })
    @Override
    public Enumeration<String> doGetAttributeNames()
    {
        List<String> names=_attributes==null?Collections.EMPTY_LIST:new ArrayList<String>(_attributes.keySet());
        return Collections.enumeration(names);
    }

    
    /* ------------------------------------------------------------ */
    @Override
    public Set<String> getNames()
    {
        synchronized (this)
        {
            return new HashSet<String>(_attributes.keySet());
        }
    }
   
    
    /* ------------------------------------------------------------- */
    @Override
    public void clearAttributes()
    {
        while (_attributes!=null && _attributes.size()>0)
        {
            ArrayList<String> keys;
            synchronized(this)
            {
                keys=new ArrayList<String>(_attributes.keySet());
            }

            Iterator<String> iter=keys.iterator();
            while (iter.hasNext())
            {
                String key=(String)iter.next();

                Object value;
                synchronized(this)
                {
                    value=doPutOrRemove(key,null);
                }
                unbindValue(key,value);

                ((AbstractSessionManager)getSessionManager()).doSessionAttributeListeners(this,key,value,null);
            }
        }
        if (_attributes!=null)
            _attributes.clear();
    }

    /* ------------------------------------------------------------ */
    public void addAttributes(Map<String,Object> map)
    {
        _attributes.putAll(map);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Object doPutOrRemove(String name, Object value)
    {
        return value==null?_attributes.remove(name):_attributes.put(name,value);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Object doGet(String name)
    {
        return _attributes.get(name);
    }
    
}
