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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* ------------------------------------------------------------ */
/** A multi valued Map.
 * 
 */
public class MultiMap implements Map<String,Object>
{
    Map<String,Object> _map;

    public MultiMap()
    {
        _map=new HashMap<String, Object>();
    }

    public MultiMap(Map<String,Object> map)
    {
        _map=new HashMap<String, Object>(map);
    }

    public MultiMap(MultiMap map)
    {
        _map=new HashMap<String,Object>(map._map);
    }


    /* ------------------------------------------------------------ */
    /** Get multiple values.
     * Single valued entries are converted to singleton lists.
     * @param name The entry key. 
     * @return Unmodifieable List of values.
     */
    public List getValues(String name)
    {
        return LazyList.getList(_map.get(name),true);
    }
    
    /* ------------------------------------------------------------ */
    /** Get a value from a multiple value.
     * If the value is not a multivalue, then index 0 retrieves the
     * value or null.
     * @param name The entry key.
     * @param i Index of element to get.
     * @return Unmodifieable List of values.
     */
    public Object getValue(String name,int i)
    {
        Object l=_map.get(name);
        if (i==0 && LazyList.size(l)==0)
            return null;
        return LazyList.get(l,i);
    }
    
    
    /* ------------------------------------------------------------ */
    /** Get value as String.
     * Single valued items are converted to a String with the toString()
     * Object method. Multi valued entries are converted to a comma separated
     * List.  No quoting of commas within values is performed.
     * @param name The entry key. 
     * @return String value.
     */
    public String getString(String name)
    {
        Object l=_map.get(name);
        switch(LazyList.size(l))
        {
          case 0:
              return null;
          case 1:
              Object o=LazyList.get(l,0);
              return o==null?null:o.toString();
          default:
          {
              StringBuilder values=new StringBuilder(128);
              for (int i=0; i<LazyList.size(l); i++)              
              {
                  Object e=LazyList.get(l,i);
                  if (e!=null)
                  {
                      if (values.length()>0)
                          values.append(',');
                      values.append(e.toString());
                  }
              }   
              return values.toString();
          }
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Object get(Object name) 
    {
        Object l=_map.get(name);
        switch(LazyList.size(l))
        {
          case 0:
              return null;
          case 1:
              Object o=LazyList.get(l,0);
              return o;
          default:
              return LazyList.getList(l,true);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Put and entry into the map.
     * @param name The entry key. 
     * @param value The entry value.
     * @return The previous value or null.
     */
    @Override
    public Object put(String name, Object value) 
    {
        return _map.put(name,LazyList.add(null,value));
    }

    /* ------------------------------------------------------------ */
    /** Put multi valued entry.
     * @param name The entry key. 
     * @param values The List of multiple values.
     * @return The previous value or null.
     */
    public Object putValues(String name, List<? extends Object> values) 
    {
        return _map.put(name,values);
    }
    
    /* ------------------------------------------------------------ */
    /** Put multi valued entry.
     * @param name The entry key. 
     * @param values The String array of multiple values.
     * @return The previous value or null.
     */
    public Object putValues(String name, String... values) 
    {
        Object list=null;
        for (int i=0;i<values.length;i++)
            list=LazyList.add(list,values[i]);
        return _map.put(name,list);
    }
    
    
    /* ------------------------------------------------------------ */
    /** Add value to multi valued entry.
     * If the entry is single valued, it is converted to the first
     * value of a multi valued entry.
     * @param name The entry key. 
     * @param value The entry value.
     */
    public void add(String name, Object value) 
    {
        Object lo = _map.get(name);
        Object ln = LazyList.add(lo,value);
        if (lo!=ln)
            _map.put(name,ln);
    }

    /* ------------------------------------------------------------ */
    /** Add values to multi valued entry.
     * If the entry is single valued, it is converted to the first
     * value of a multi valued entry.
     * @param name The entry key. 
     * @param values The List of multiple values.
     */
    public void addValues(String name, List<? extends Object> values) 
    {
        Object lo = _map.get(name);
        Object ln = LazyList.addCollection(lo,values);
        if (lo!=ln)
            _map.put(name,ln);
    }
    
    /* ------------------------------------------------------------ */
    /** Add values to multi valued entry.
     * If the entry is single valued, it is converted to the first
     * value of a multi valued entry.
     * @param name The entry key. 
     * @param values The String array of multiple values.
     */
    public void addValues(String name, String[] values) 
    {
        Object lo = _map.get(name);
        Object ln = LazyList.addCollection(lo,Arrays.asList(values));
        if (lo!=ln)
            _map.put(name,ln);
    }
    
    /* ------------------------------------------------------------ */
    /** Remove value.
     * @param name The entry key. 
     * @param value The entry value. 
     * @return true if it was removed.
     */
    public boolean removeValue(String name,Object value)
    {
        Object lo = _map.get(name);
        Object ln=lo;
        int s=LazyList.size(lo);
        if (s>0)
        {
            ln=LazyList.remove(lo,value);
            if (ln==null)
                _map.remove(name);
            else
                _map.put(name, ln);
        }
        return LazyList.size(ln)!=s;
    }
    
    
    /* ------------------------------------------------------------ */
    /** Put all contents of map.
     * @param m Map
     */
    @Override
    public void putAll(Map<? extends String, ? extends Object> m)
    {
        boolean multi = (m instanceof MultiMap);

        if (multi)
        {
            for (Map.Entry<? extends String, ? extends Object> entry : m.entrySet())
            {
                _map.put(entry.getKey(),LazyList.clone(entry.getValue()));
            }
        }
        else
        {
            _map.putAll(m);
        }
    }

    /* ------------------------------------------------------------ */
    /** 
     * @return Map of String arrays
     */
    public Map<String,String[]> toStringArrayMap()
    {
        HashMap<String,String[]> map = new HashMap<String,String[]>(_map.size()*3/2)
        {
            @Override
            public String toString()
            {
                StringBuilder b=new StringBuilder();
                b.append('{');
                for (String k:super.keySet())
                {
                    if(b.length()>1)
                        b.append(',');
                    b.append(k);
                    b.append('=');
                    b.append(Arrays.asList(super.get(k)));
                }

                b.append('}');
                return b.toString();
            }
        };
        
        for(Map.Entry<String,Object> entry: _map.entrySet())
        {
            String[] a = LazyList.toStringArray(entry.getValue());
            map.put(entry.getKey(),a);
        }
        return map;
    }

    @Override
    public String toString()
    {
        return _map.toString();
    }
    
    @Override
    public void clear()
    {
        _map.clear();
    }

    @Override
    public boolean containsKey(Object key)
    {
        return _map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value)
    {
        return _map.containsValue(value);
    }

    @Override
    public Set<Entry<String, Object>> entrySet()
    {
        return _map.entrySet();
    }

    @Override
    public boolean equals(Object o)
    {
        return _map.equals(o);
    }

    @Override
    public int hashCode()
    {
        return _map.hashCode();
    }

    @Override
    public boolean isEmpty()
    {
        return _map.isEmpty();
    }

    @Override
    public Set<String> keySet()
    {
        return _map.keySet();
    }

    @Override
    public Object remove(Object key)
    {
        return _map.remove(key);
    }

    @Override
    public int size()
    {
        return _map.size();
    }

    @Override
    public Collection<Object> values()
    {
        return _map.values();
    }
}
