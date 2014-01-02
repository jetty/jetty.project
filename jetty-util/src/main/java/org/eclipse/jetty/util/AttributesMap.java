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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* ------------------------------------------------------------ */
/** AttributesMap.
 * 
 *
 */
public class AttributesMap implements Attributes
{
    protected final Map<String,Object> _map;

    /* ------------------------------------------------------------ */
    public AttributesMap()
    {
        _map=new HashMap<String,Object>();
    }
    
    /* ------------------------------------------------------------ */
    public AttributesMap(Map<String,Object> map)
    {
        _map=map;
    }

    /* ------------------------------------------------------------ */
    public AttributesMap(AttributesMap map)
    {
        _map=new HashMap<String,Object>(map._map);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#removeAttribute(java.lang.String)
     */
    public void removeAttribute(String name)
    {
        _map.remove(name);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#setAttribute(java.lang.String, java.lang.Object)
     */
    public void setAttribute(String name, Object attribute)
    {
        if (attribute==null)
            _map.remove(name);
        else
            _map.put(name, attribute);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        return _map.get(name);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#getAttributeNames()
     */
    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(_map.keySet());
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#getAttributeNames()
     */
    public Set<String> getAttributeNameSet()
    {
        return _map.keySet();
    }
    
    /* ------------------------------------------------------------ */
    public Set<Map.Entry<String, Object>> getAttributeEntrySet()
    {
        return _map.entrySet();
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#getAttributeNames()
     */
    public static Enumeration<String> getAttributeNamesCopy(Attributes attrs)
    {
        if (attrs instanceof AttributesMap)
            return Collections.enumeration(((AttributesMap)attrs)._map.keySet());
        
        List<String> names = new ArrayList<String>();
        names.addAll(Collections.list(attrs.getAttributeNames()));
        return Collections.enumeration(names);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#clear()
     */
    public void clearAttributes()
    {
        _map.clear();
    }
    
    /* ------------------------------------------------------------ */
    public int size()
    {
        return _map.size();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _map.toString();
    }
    
    /* ------------------------------------------------------------ */
    public Set<String> keySet()
    {
        return _map.keySet();
    }
    
    /* ------------------------------------------------------------ */
    public void addAll(Attributes attributes)
    {
        Enumeration<String> e = attributes.getAttributeNames();
        while (e.hasMoreElements())
        {
            String name=e.nextElement();
            setAttribute(name,attributes.getAttribute(name));
        }
    }

}
