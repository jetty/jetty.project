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


package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;


/* ------------------------------------------------------------ */
/**
 * ClasspathPattern performs sequential pattern matching of a class name 
 * against an internal array of classpath pattern entries.
 * 
 * When an entry starts with '-' (minus), reverse matching is performed.
 * When an entry ends with '.' (period), prefix matching is performed.
 * 
 * When class is initialized from a classpath pattern string, entries 
 * in this string should be separated by ':' (semicolon) or ',' (comma).
 */

public class ClasspathPattern
{
    private static class Entry
    {
        public String classpath = null;
        public boolean result = false;
        public boolean partial = false;      
    }
    
    final private List<String> _patterns = new ArrayList<String>();
    final private List<Entry> _entries = new ArrayList<Entry>();
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern()
    {
    }
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern(String[] patterns)
    {
        setPatterns(patterns);
    }
    
    /* ------------------------------------------------------------ */
    public ClasspathPattern(String pattern)
    {
        setPattern(pattern);
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing each classpath pattern in an array
     * 
     * @param patterns array of classpath patterns
     */
    private void setPatterns(String[] patterns)
    {
        _patterns.clear();
        _entries.clear();
        addPatterns(patterns);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing each classpath pattern in an array
     * 
     * @param patterns array of classpath patterns
     */
    private void addPatterns(String[] patterns)
    {
        if (patterns != null)
        {
            Entry entry = null; 
            for (String pattern : patterns)
            {
                entry = createEntry(pattern);
                if (entry != null) {
                    _patterns.add(pattern);
                    _entries.add(entry);
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Create an entry object containing information about 
     * a single classpath pattern
     * 
     * @param pattern single classpath pattern
     * @return corresponding Entry object
     */
    private Entry createEntry(String pattern)
    {
        Entry entry = null;
        
        if (pattern != null)
        {
            String item = pattern.trim();
            if (item.length() > 0)
            {
                entry = new Entry();
                entry.result = !item.startsWith("-");
                entry.partial = item.endsWith(".");
                entry.classpath = entry.result ? item : item.substring(1).trim();
            }
        }
        return entry;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing a classpath pattern string
     * 
     * @param pattern classpath pattern string
     */
    public void setPattern(String pattern)
    {
        _patterns.clear();
        _entries.clear();
        addPattern(pattern);
    }

    /* ------------------------------------------------------------ */
    /**
     * Parse a classpath pattern string and appending the result
     * to the existing configuration.
     * 
     * @param pattern classpath pattern string
     */
    public void addPattern(String pattern)
    {
        ArrayList<String> patterns = new ArrayList<String>();
        StringTokenizer entries = new StringTokenizer(pattern, ":,");
        while (entries.hasMoreTokens())
        {
            patterns.add(entries.nextToken());
        }
        
        addPatterns((String[])patterns.toArray(new String[patterns.size()]));
    }   
    
    /* ------------------------------------------------------------ */
    /**
     * @return array of classpath patterns
     */
    public String[] getPatterns()
    {
        String[] patterns = null;
        
        if (_patterns!=null && _patterns.size() > 0)
        {
            patterns = _patterns.toArray(new String[_patterns.size()]);
        }
        
        return patterns;
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
        boolean result=false;

        if (_entries != null)
        {
            name = name.replace('/','.');

            int startIndex = 0;

            while(startIndex < name.length() && name.charAt(startIndex) == '.') {
                startIndex++;
            }

            int dollar = name.indexOf("$");

            int endIndex =  dollar != -1 ? dollar : name.length();

            for (Entry entry : _entries)
            {
                if (entry != null)
                {               
                    if (entry.partial)
                    {
                        if (name.regionMatches(startIndex, entry.classpath, 0, entry.classpath.length()))
                        {
                            result = entry.result;
                            break;
                        }
                    }
                    else
                    {
                        int regionLength = endIndex-startIndex;
                        if (regionLength == entry.classpath.length()
                                && name.regionMatches(startIndex, entry.classpath, 0, regionLength))
                        {
                            result = entry.result;
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }
}
