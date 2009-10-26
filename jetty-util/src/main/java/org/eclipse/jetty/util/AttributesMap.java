// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/* ------------------------------------------------------------ */
/** AttributesMap.
 * 
 *
 */
public class AttributesMap implements Attributes
{
    Map _map;

    /* ------------------------------------------------------------ */
    public AttributesMap()
    {
        _map=new HashMap();
    }
    
    /* ------------------------------------------------------------ */
    public AttributesMap(Map map)
    {
        _map=map;
    }
    
    public AttributesMap(AttributesMap map)
    {
        _map=new HashMap(map._map);
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
    public Enumeration getAttributeNames()
    {
        return Collections.enumeration(_map.keySet());
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.util.Attributes#getAttributeNames()
     */
    public static Enumeration getAttributeNamesCopy(Attributes attrs)
    {
        if (attrs instanceof AttributesMap)
            return Collections.enumeration(((AttributesMap)attrs)._map.keySet());
        ArrayList names = new ArrayList();
        Enumeration e = attrs.getAttributeNames();
        while (e.hasMoreElements())
            names.add(e.nextElement());
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

}
