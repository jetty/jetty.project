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


package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

public class ClasspathPattern
{
    private static class Entry
    {
        final String _pattern;
        final String _name;
        final boolean _inclusive;
        final boolean _package;     
        
        Entry(String pattern)
        {
            _pattern=pattern;
            _inclusive = !pattern.startsWith("-");
            _package = pattern.endsWith(".");
            _name = _inclusive ? pattern : pattern.substring(1).trim();
        }
        
        boolean isInclusive()
        {
            return _inclusive;
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
    public ClasspathPattern(String... patterns)
    {
        if (patterns!=null && patterns.length>0)
            add(patterns);
    }
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern(String pattern)
    {
        add(pattern);
    }
    
    /* ------------------------------------------------------------ */
    public void add(String... patterns)
    {
        if (patterns==null || patterns.length==0)
            return;
        
        for (String p :patterns)
        {
            if (_entries.stream().anyMatch(e->{return p.equals(e.toString());}))
                continue;
                
            Entry e = new Entry(p);
            if (e.isInclusive())
                _entries.add(e);
            else
                _entries.add(0,e);
        }        
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
    /**
     * @return array of classpath patterns
     */
    public String[] toArray()
    {
        return _entries.stream().map(e->{return e.toString();}).toArray(String[]::new);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return array of classpath patterns
     */
    public List<String> getPatterns()
    {
        return _entries.stream().map(e->{return e.toString();}).collect(Collectors.toList());
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
                if (name.startsWith(entry._name))
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

}
