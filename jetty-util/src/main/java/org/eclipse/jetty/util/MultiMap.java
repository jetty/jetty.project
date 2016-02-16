//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** 
 * A multi valued Map.
 * @param <V> the entry type for multimap values
 */
@SuppressWarnings("serial")
public class MultiMap<V> extends HashMap<String,List<V>>
{
    public MultiMap()
    {
        super();
    }

    public MultiMap(Map<String,List<V>> map)
    {
        super(map);
    }

    public MultiMap(MultiMap<V> map)
    {
        super(map);
    }


    /* ------------------------------------------------------------ */
    /** Get multiple values.
     * Single valued entries are converted to singleton lists.
     * @param name The entry key. 
     * @return Unmodifieable List of values.
     */
    public List<V> getValues(String name)
    {
        List<V> vals = super.get(name);
        if((vals == null) || vals.isEmpty()) {
            return null;
        }
        return vals;
    }
    
    /* ------------------------------------------------------------ */
    /** Get a value from a multiple value.
     * If the value is not a multivalue, then index 0 retrieves the
     * value or null.
     * @param name The entry key.
     * @param i Index of element to get.
     * @return Unmodifieable List of values.
     */
    public V getValue(String name,int i)
    {
        List<V> vals = getValues(name);
        if(vals == null) {
            return null;
        }
        if (i==0 && vals.isEmpty()) {
            return null;
        }
        return vals.get(i);
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
        List<V> vals =get(name);
        if ((vals == null) || (vals.isEmpty()))
        {
            return null;
        }
        
        if (vals.size() == 1)
        {
            // simple form.
            return vals.get(0).toString();
        }
        
        // delimited form
        StringBuilder values=new StringBuilder(128);
        for (V e : vals)
        {
            if (e != null)
            {
                if (values.length() > 0)
                    values.append(',');
                values.append(e.toString());
            }
        }   
        return values.toString();
    }
    
    /** 
     * Put multi valued entry.
     * @param name The entry key. 
     * @param value The simple value
     * @return The previous value or null.
     */
    public List<V> put(String name, V value) 
    {
        if(value == null) {
            return super.put(name, null);
        }
        List<V> vals = new ArrayList<>();
        vals.add(value);
        return put(name,vals);
    }

    /**
     * Shorthand version of putAll
     * @param input the input map
     */
    public void putAllValues(Map<String, V> input)
    {
        for(Map.Entry<String,V> entry: input.entrySet())
        {
            put(entry.getKey(), entry.getValue());
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Put multi valued entry.
     * @param name The entry key. 
     * @param values The List of multiple values.
     * @return The previous value or null.
     */
    public List<V> putValues(String name, List<V> values) 
    {
        return super.put(name,values);
    }
    
    /* ------------------------------------------------------------ */
    /** Put multi valued entry.
     * @param name The entry key. 
     * @param values The array of multiple values.
     * @return The previous value or null.
     */
    @SafeVarargs
    public final List<V> putValues(String name, V... values) 
    {
        List<V> list = new ArrayList<>();
        list.addAll(Arrays.asList(values));
        return super.put(name,list);
    }
    
    
    /* ------------------------------------------------------------ */
    /** Add value to multi valued entry.
     * If the entry is single valued, it is converted to the first
     * value of a multi valued entry.
     * @param name The entry key. 
     * @param value The entry value.
     */
    public void add(String name, V value) 
    {
        List<V> lo = get(name);
        if(lo == null) {
            lo = new ArrayList<>();
        }
        lo.add(value);
        super.put(name,lo);
    }

    /* ------------------------------------------------------------ */
    /** Add values to multi valued entry.
     * If the entry is single valued, it is converted to the first
     * value of a multi valued entry.
     * @param name The entry key. 
     * @param values The List of multiple values.
     */
    public void addValues(String name, List<V> values) 
    {
        List<V> lo = get(name);
        if(lo == null) {
            lo = new ArrayList<>();
        }
        lo.addAll(values);
        put(name,lo);
    }
    
    /* ------------------------------------------------------------ */
    /** Add values to multi valued entry.
     * If the entry is single valued, it is converted to the first
     * value of a multi valued entry.
     * @param name The entry key. 
     * @param values The String array of multiple values.
     */
    public void addValues(String name, V[] values) 
    {
        List<V> lo = get(name);
        if(lo == null) {
            lo = new ArrayList<>();
        }
        lo.addAll(Arrays.asList(values));
        put(name,lo);
    }
    
    /**
     * Merge values.
     * 
     * @param map
     *            the map to overlay on top of this one, merging together values if needed.
     * @return true if an existing key was merged with potentially new values, false if either no change was made, or there were only new keys.
     */
    public boolean addAllValues(MultiMap<V> map)
    {
        boolean merged = false;

        if ((map == null) || (map.isEmpty()))
        {
            // done
            return merged;
        }

        for (Map.Entry<String, List<V>> entry : map.entrySet())
        {
            String name = entry.getKey();
            List<V> values = entry.getValue();

            if (this.containsKey(name))
            {
                merged = true;
            }

            this.addValues(name,values);
        }

        return merged;
    }
    
    /* ------------------------------------------------------------ */
    /** Remove value.
     * @param name The entry key. 
     * @param value The entry value. 
     * @return true if it was removed.
     */
    public boolean removeValue(String name,V value)
    {
        List<V> lo = get(name);
        if((lo == null)||(lo.isEmpty())) {
            return false;
        }
        boolean ret = lo.remove(value);
        if(lo.isEmpty()) {
            remove(name);
        } else {
            put(name,lo);
        }
        return ret;
    }
    
    /**
     * Test for a specific single value in the map.
     * <p>
     * NOTE: This is a SLOW operation, and is actively discouraged.
     * @param value the value to search for
     * @return true if contains simple value
     */
    public boolean containsSimpleValue(V value)
    {
        for (List<V> vals : values())
        {
            if ((vals.size() == 1) && vals.contains(value))
            {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public String toString()
    {
        Iterator<Entry<String, List<V>>> iter = entrySet().iterator();
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean delim = false;
        while (iter.hasNext())
        {
            Entry<String, List<V>> e = iter.next();
            if (delim)
            {
                sb.append(", ");
            }
            String key = e.getKey();
            List<V> vals = e.getValue();
            sb.append(key);
            sb.append('=');
            if (vals.size() == 1)
            {
                sb.append(vals.get(0));
            }
            else
            {
                sb.append(vals);
            }
            delim = true;
        }
        sb.append('}');
        return sb.toString();
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * @return Map of String arrays
     */
    public Map<String,String[]> toStringArrayMap()
    {
        HashMap<String,String[]> map = new HashMap<String,String[]>(size()*3/2)
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
        
        for(Map.Entry<String,List<V>> entry: entrySet())
        {
            String[] a = null;
            if (entry.getValue() != null)
            {
                a = new String[entry.getValue().size()];
                a = entry.getValue().toArray(a);
            }
            map.put(entry.getKey(),a);
        }
        return map;
    }

}
