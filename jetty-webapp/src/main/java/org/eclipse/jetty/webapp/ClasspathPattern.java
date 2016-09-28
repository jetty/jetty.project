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

import java.io.File;
import java.nio.file.Path;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;

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

public class ClasspathPattern extends AbstractSet<String>
{
    enum Type { PACKAGE, CLASSNAME, LOCATION }

    private static class Entry
    {
        private final String _pattern;
        private final String _name;
        private final boolean _inclusive;
        private final Type _type;
        
        Entry(String pattern)
        {
            _pattern=pattern;
            _inclusive = !pattern.startsWith("-");
            _name = _inclusive ? pattern : pattern.substring(1).trim();
            _type = (_name.startsWith("file:"))?Type.LOCATION:(_name.endsWith(".")?Type.PACKAGE:Type.CLASSNAME);
        }
        
        public boolean isPackage()
        {
            return _type==Type.PACKAGE;
        }
        
        public boolean isClassName()
        {
            return _type==Type.CLASSNAME;
        }
        
        public boolean isLocation()
        {
            return _type==Type.LOCATION;
        }

        public String getName()
        {
            return _name;
        }
        
        @Override
        public String toString()
        {
            return _pattern;
        }
        
        @Override 
        public int hashCode()
        {
            return _pattern.hashCode();
        }
        
        @Override 
        public boolean equals(Object o)
        {
            return (o instanceof Entry) 
                && _pattern.equals(((Entry)o)._pattern);
        }

        public boolean isInclusive()
        {
            return _inclusive;
        }
    }
    
    
    public static class ByPackage extends AbstractSet<Entry> implements Predicate<String> 
    {
        private final ArrayTernaryTrie.Growing<Entry> _entries = new ArrayTernaryTrie.Growing<>(false,512,512);

        @Override
        public boolean test(String name)
        {
            return _entries.getBest(name)!=null;
        }

        @Override
        public Iterator<Entry> iterator()
        {
            return _entries.keySet().stream().map(k->_entries.get(k)).iterator();
        }

        @Override
        public int size()
        {
            return _entries.size();
        }
        
        @Override
        public boolean isEmpty()
        {
            return _entries.isEmpty();
        }
        
        @Override
        public boolean add(Entry entry)
        {
            String name = entry.getName();
            if (entry.isClassName())
                name+="$";
            else if (entry.isLocation())
                throw new IllegalArgumentException(entry.toString());
            else if (".".equals(name))
                name="";
                
            if (_entries.get(name)!=null)
                return false;
            
            _entries.put(name,entry);
            return true;
        }
        
        @Override
        public boolean remove(Object entry)
        {
            if (!(entry instanceof Entry))
                return false;

            return _entries.remove(((Entry)entry).getName())!=null;
        }
        
        @Override
        public void clear()
        {
            _entries.clear();
        }
    }
    
    @SuppressWarnings("serial")
    public static class ByName extends HashSet<Entry> implements Predicate<String> 
    {
        private final Map<String,Entry> _entries = new HashMap<>();

        @Override
        public boolean test(String name)
        {
            return _entries.containsKey(name);
        }

        @Override
        public Iterator<Entry> iterator()
        {
            return _entries.values().iterator();
        }

        @Override
        public int size()
        {
            return _entries.size();
        }
        
        @Override
        public boolean add(Entry entry)
        {
            if (!entry.isClassName())
                throw new IllegalArgumentException(entry.toString());
            return _entries.put(entry.getName(),entry)==null;
        }
        
        @Override
        public boolean remove(Object entry)
        {
            if (!(entry instanceof Entry))
                return false;

            return _entries.remove(((Entry)entry).getName())!=null;
        }
    }

    public static class ByPackageOrName extends AbstractSet<Entry> implements Predicate<String> 
    {
        private final ByName _byName = new ByName();
        private final ByPackage _byPackage = new ByPackage();
        
        @Override
        public boolean test(String name)
        {
            return  _byPackage.test(name) 
                || _byName.test(name) ;
        }

        @Override
        public Iterator<Entry> iterator()
        {
            // by package contains all entries (classes are also $ packages).
            return _byPackage.iterator();
        }

        @Override
        public int size()
        {
            return _byPackage.size();
        }

        @Override
        public boolean add(Entry e)
        {
            if (e.isLocation())
                throw new IllegalArgumentException();
            
            if (e.isPackage())
                return _byPackage.add(e);
            
            // Add class name to packages also as classes act
            // as packages for nested classes.
            boolean added = _byPackage.add(e);
            added = _byName.add(e) || added;
            return added;
        }

        @Override
        public boolean remove(Object o)
        {
            if (!(o instanceof Entry))
                return false;

            boolean removed = _byPackage.remove(o);
            
            if (!((Entry)o).isPackage())
                removed = _byName.remove(o) || removed;
            
            return removed;
        }

        @Override
        public void clear()
        {
            _byPackage.clear();
            _byName.clear();
        }
    }
    
    @SuppressWarnings("serial")
    public static class ByLocation extends HashSet<File> implements Predicate<Path>
    {        
        @Override
        public boolean test(Path path)
        {
            for (File file: this)
            {
                if (file.isDirectory())
                {
                    if (path.startsWith(file.toPath()))
                        return true;
                }
                else
                {
                    if (path.equals(file.toPath()))
                        return true;
                }
            }
                
            return false;
        }
    }
    
    
    Map<String,Entry> _entries = new HashMap<>();
    Set<String> _classes = new HashSet<>();
    
    IncludeExcludeSet<Entry,String> _patterns = new IncludeExcludeSet<>(ByPackageOrName.class);
    IncludeExcludeSet<File,Path> _locations = new IncludeExcludeSet<>(ByLocation.class);
    
    public ClasspathPattern()
    {
    }
    
    public ClasspathPattern(String[] patterns)
    {
        setAll(patterns);
    }
    
    public ClasspathPattern(String pattern)
    {
        add(pattern);
    }

    @Override
    public boolean add(String pattern)
    {
        if (_entries.containsKey(pattern))
            return false;
        Entry entry = new Entry(pattern);
        _entries.put(pattern,entry);

        if (entry.isLocation())
        {
            try
            {
                File file = Resource.newResource(entry.getName()).getFile().getAbsoluteFile().getCanonicalFile();
                if (entry.isInclusive())
                    _locations.include(file);
                else
                    _locations.exclude(file);
            }
            catch (Exception e)
            {
                throw new IllegalArgumentException(e);
            }
        }
        else
        {
            if (entry.isInclusive())
                _patterns.include(entry);
            else
                _patterns.exclude(entry);
        }
        return true;
    }

    @Override
    public boolean remove(Object o)
    {
        if (!(o instanceof String))
            return false;
        String pattern = (String)o;

        Entry entry = _entries.remove(pattern);
        if (entry==null)
            return false;

        if (entry.isInclusive())
            _patterns.getIncluded().remove(entry.getName());
        else
            _patterns.getExcluded().remove(entry.getName());
        
        return true;
    }

    @Override
    public void clear()
    {
        _entries.clear();
        _patterns.clear();
    }

    @Override
    public Iterator<String> iterator()
    {
        return _entries.keySet().iterator();
    }

    public boolean remove(String pattern)
    {
        return _entries.remove(pattern)!=null;
    }

    @Override
    public int size()
    {
        return _entries.size();
    }

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
    
    /**
     * @param classes array of classpath patterns
     */
    private void addAll(String[] classes)
    {
        if (classes!=null)
            addAll(Arrays.asList(classes));
    }
    
    /**
     * @return array of classpath patterns
     */
    public String[] getPatterns()
    {
        return toArray(new String[_entries.size()]);
    }

    
    /**
     * Match the class name against the pattern
     *
     * @param name name of the class to match
     * @return true if class matches the pattern
     */
    public boolean match(String name)
    {       
        return _patterns.test(name);
    }
    
    /**
     * Match the class name against the pattern
     *
     * @param name name of the class to match
     * @return true if class matches the pattern
     */
    public boolean match(Class<?> clazz)
    {       
        try
        {
            Resource resource = TypeUtil.getLoadedFrom(clazz);
            Path path = resource.getFile().toPath();
            
            Boolean inPatterns = _patterns.isIncludedAndNotExcluded(clazz.getName());
            Boolean inLocations = _locations.isIncludedAndNotExcluded(path);
            
            return (inPatterns==Boolean.TRUE  || inLocations==Boolean.TRUE)
                &&!(inPatterns==Boolean.FALSE || inLocations==Boolean.FALSE);
            
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        
    }

}
