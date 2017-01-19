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


package org.eclipse.jetty.webapp;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.util.StringUtil;

/* ------------------------------------------------------------ */
/**
 * Classpath classes list performs sequential pattern matching of a class name 
 * against an internal array of classpath pattern entries.
 * A class pattern is a string of one of the forms:<ul>
 * <li>'org.package.SomeClass' will match a specific class
 * <li>'org.package.' will match a specific package hierarchy
 * <li>'-org.package.Classname' excludes a specific class
 * <li>'-org.package.' excludes a specific package hierarchy
 * <li>Nested classes must be specified with the '$' separator if they 
 * are to be explicitly included or excluded (eg. org.example.MyClass$NestedClass).
 * <li>Nested classes are matched by their containing class. (eg. -org.example.MyClass
 * would exclude org.example.MyClass$AnyNestedClass)
 * </ul>
 * When class is initialized from a classpath pattern string, entries 
 * in this string should be separated by ':' (semicolon) or ',' (comma).
 */

public class ClasspathPattern extends AbstractList<String>
{
    private static class Entry
    {
        public final String _pattern;
        public final String _name;
        public final boolean _inclusive;
        public final boolean _package;     
        
        Entry(String pattern)
        {
            _pattern=pattern;
            _inclusive = !pattern.startsWith("-");
            _package = pattern.endsWith(".");
            _name = _inclusive ? pattern : pattern.substring(1).trim();
        }
        
        @Override
        public String toString()
        {
            return _pattern;
        }
    }
    
    final private List<Entry> _entries = new ArrayList<Entry>();
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern()
    {
    }
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern(String[] patterns)
    {
        setAll(patterns);
    }
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern(String pattern)
    {
        add(pattern);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String get(int index)
    {
        return _entries.get(index)._pattern;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String set(int index, String element)
    {
        Entry e = _entries.set(index,new Entry(element));
        return e==null?null:e._pattern;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void add(int index, String element)
    {
        _entries.add(index,new Entry(element));
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void addPattern(String element)
    {
        add(element);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String remove(int index)
    {
        Entry e = _entries.remove(index);
        return e==null?null:e._pattern;
    }
    
    /* ------------------------------------------------------------ */
    public boolean remove(String pattern)
    {
        for (int i=_entries.size();i-->0;)
        {
            if (pattern.equals(_entries.get(i)._pattern))
            {
                _entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _entries.size();
    }

    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing each classpath pattern in an array
     * 
     * @param classes array of classpath patterns
     */
    private void setAll(String[] classes)
    {
        _entries.clear();
        addAll(classes);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param classes array of classpath patterns
     */
    private void addAll(String[] classes)
    {
        if (classes!=null)
            addAll(Arrays.asList(classes));
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param classes array of classpath patterns
     */
    public void prepend(String[] classes)
    {
        if (classes != null)
        {
            int i=0;
            for (String c : classes)
            {
                add(i,c);
                i++;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void prependPattern(String pattern)
    {
        add(0,pattern);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return array of classpath patterns
     */
    public String[] getPatterns()
    {
        return toArray(new String[_entries.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return List of classes excluded class exclusions and package patterns
     */
    public List<String> getClasses()
    {
        List<String> list = new ArrayList<>();
        for (Entry e:_entries)
        {
            if (e._inclusive && !e._package)
                list.add(e._name);
        }
        return list;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Match the class name against the pattern
     *
     * @param name name of the class to match
     * @return true if class matches the pattern
     */
    public boolean match(String name)
    {       
        name = name.replace('/','.');

        for (Entry entry : _entries)
        {
            if (entry==null)
                continue;
            if (entry._package)
            {
                if (name.startsWith(entry._name) || ".".equals(entry._pattern))
                    return entry._inclusive;
            }
            else
            {
                if (name.equals(entry._name))
                    return entry._inclusive;
                
                if (name.length()>entry._name.length() && '$'==name.charAt(entry._name.length()) && name.startsWith(entry._name))
                    return entry._inclusive;
            }
        }
        return false;
    }

    public void addAfter(String afterPattern,String... patterns)
    {
        if (patterns!=null && afterPattern!=null)
        {
            ListIterator<String> iter = listIterator();
            while (iter.hasNext())
            {
                String cc=iter.next();
                if (afterPattern.equals(cc))
                {
                    for (int i=0;i<patterns.length;i++)
                        iter.add(patterns[i]);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("after '"+afterPattern+"' not found in "+this);
    }

    public void addBefore(String beforePattern,String... patterns)
    {
        if (patterns!=null && beforePattern!=null)
        {
            ListIterator<String> iter = listIterator();
            while (iter.hasNext())
            {
                String cc=iter.next();
                if (beforePattern.equals(cc))
                {
                    iter.previous();
                    for (int i=0;i<patterns.length;i++)
                        iter.add(patterns[i]);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("before '"+beforePattern+"' not found in "+this);
    }
}
