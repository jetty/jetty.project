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


package org.eclipse.jetty.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/* ------------------------------------------------------------ */
/**
 */
@SuppressWarnings("serial")
public class HostMap<TYPE> extends HashMap<String, TYPE>
{

    /* --------------------------------------------------------------- */
    /** Construct empty HostMap.
     */
    public HostMap()
    {
        super(11);
    }
   
    /* --------------------------------------------------------------- */
    /** Construct empty HostMap.
     * 
     * @param capacity initial capacity
     */
    public HostMap(int capacity)
    {
        super (capacity);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see java.util.HashMap#put(java.lang.Object, java.lang.Object)
     */
    @Override
    public TYPE put(String host, TYPE object)
        throws IllegalArgumentException
    {
        return super.put(host, object);
    }
        
    /* ------------------------------------------------------------ */
    /**
     * @see java.util.HashMap#get(java.lang.Object)
     */
    @Override
    public TYPE get(Object key)
    {
        return super.get(key);
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve a lazy list of map entries associated with specified
     * hostname by taking into account the domain suffix matches.
     * 
     * @param host hostname
     * @return lazy list of map entries
     */
    public Object getLazyMatches(String host)
    {
        if (host == null)
            return LazyList.getList(super.entrySet());
        
        int idx = 0;
        String domain = host.trim();
        HashSet<String> domains = new HashSet<String>();
        do {
            domains.add(domain);
            if ((idx = domain.indexOf('.')) > 0)
            {
                domain = domain.substring(idx+1);
            }
        } while (idx > 0);
        
        Object entries = null;
        for(Map.Entry<String, TYPE> entry: super.entrySet())
        {
            if (domains.contains(entry.getKey()))
            {
                entries = LazyList.add(entries,entry);
            }
        }
       
        return entries;        
    }

}
