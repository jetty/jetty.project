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

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/* ------------------------------------------------------------ */
/** Map implementation Optimized for Strings keys..
 * This String Map has been optimized for mapping small sets of
 * Strings where the most frequently accessed Strings have been put to
 * the map first.
 *
 * It also has the benefit that it can look up entries by substring or
 * sections of char and byte arrays.  This can prevent many String
 * objects from being created just to look up in the map.
 *
 * This map is NOT synchronized.
 * @deprecated Use {@link Trie}
 */
public class StringMap<O> extends AbstractMap<String,O>
{
    private final TreeMap<Object, O> _map;
    
    
    public static final boolean CASE_INSENSTIVE=true;
    
    /* ------------------------------------------------------------ */

    private final boolean _caseInsensitive;
    
    
    /* ------------------------------------------------------------ */
    /** Constructor. 
     */
    public StringMap()
    {
        this(false);
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor. 
     * @param ignoreCase 
     */
    public StringMap(final boolean ignoreCase)
    {
        _caseInsensitive=ignoreCase;
        _map = new TreeMap<Object,O>(new Comparator<Object>()
        {
            @Override
            public int compare(Object o1, Object o2)
            {
                String s1=(o1 instanceof String)?(String)o1:null;
                ByteBuffer b1=(o1 instanceof ByteBuffer)?(ByteBuffer)o1:null;
                if (s1==null && b1==null)
                    s1=o1.toString();
                String s2=(String)o2;
                
                int n1 = s1==null?b1.remaining():s1.length();
                int n2 = s2.length();
                int min = Math.min(n1, n2);
                for (int i = 0; i < min; i++) {
                    char c1 = s1==null?(char)b1.get(b1.position()+i):s1.charAt(i);
                    char c2 = s2.charAt(i);
                    if (c1 != c2) {
                        if (ignoreCase)
                        {
                            c1 = Character.toUpperCase(c1);
                            c2 = Character.toUpperCase(c2);
                            if (c1 != c2) {
                                c1 = Character.toLowerCase(c1);
                                c2 = Character.toLowerCase(c2);
                                if (c1 != c2) {
                                    // No overflow because of numeric promotion
                                    return c1 - c2;
                                }
                            }
                        }
                        else
                            return c1 - c2;
                    }
                }
                return n1 - n2;
            }
        });
    }

    /* ------------------------------------------------------------ */
    public boolean isIgnoreCase()
    {
        return _caseInsensitive;
    }

    /* ------------------------------------------------------------ */
    @Override
    public O put(String key, O value)
    {
        return _map.put(key,value);
    }

    /* ------------------------------------------------------------ */
    @Override
    public O get(Object key)
    {
        return _map.get(key);
    }
    
    /* ------------------------------------------------------------ */
    public O get(String key)
    {
        return _map.get(key);
    }
    
    /* ------------------------------------------------------------ */
    public O get(String key,int offset,int length)
    {
        return _map.get(key.substring(offset,offset+length));
    }
    
    /* ------------------------------------------------------------ */
    public O get(ByteBuffer buffer)
    {
        return _map.get(buffer);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public O remove(Object key)
    {
        return _map.remove(key);
    }
    
    /* ------------------------------------------------------------ */
    public O remove(String key)
    {
        return _map.remove(key);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<Map.Entry<String,O>> entrySet()
    {
        Object o=_map.entrySet();
        return Collections.unmodifiableSet((Set<Map.Entry<String,O>>)o);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _map.size();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isEmpty()
    {
        return _map.isEmpty();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsKey(Object key)
    {
        return _map.containsKey(key);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void clear()
    {
        _map.clear();
    }

}
