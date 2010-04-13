// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.webapp;

import java.util.ArrayList;
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
    private class Entry
    {
        public String classpath = null;
        public boolean result = false;
        public boolean partial = false;      
    }
    
    private String[] _patterns = null;
    private Entry[] _entries = null;

    public ClasspathPattern(String[] patterns)
    {
        setPatterns(patterns);
    }
    
    public ClasspathPattern(String pattern)
    {
        setPattern(pattern);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Create a new instance from a String array of classpath patterns
     * 
     * @param patterns array of classpath patterns
     * @return
     */
    public static ClasspathPattern fromArray(String[] patterns)
    {
        return new ClasspathPattern(patterns);
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new instance from a classpath pattern sring
     * 
     * @param patterns classpath pattern string
     * @return
     */
    public static ClasspathPattern fromString(String patterns)
    {
        return new ClasspathPattern(patterns);
    }

    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing each classpath pattern in an array
     * 
     * @param patterns array of classpath patterns
     */
    private void setPatterns(String[] patterns)
    {
        if (patterns == null)
        {
            _patterns = null;
            _entries  = null;
        }
        else
        {
            _patterns = patterns.clone();
            _entries = new Entry[patterns.length];
        }
        
        if (_patterns != null) {
            String item = null; 
            for (int idx=0; idx<_patterns.length; idx++)
            {
                item = _patterns[idx].trim();
                if (item != null)
                {
                    Entry entry = new Entry();
                    entry.result = !item.startsWith("-");
                    entry.partial = item.endsWith(".");
                    entry.classpath = entry.result ? item : item.substring(1).trim();
                    _entries[idx] = entry;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing a classpath pattern string
     * 
     * @param pattern classpath pattern string
     */
    private void setPattern(String pattern)
    {
        ArrayList<String> patterns = new ArrayList<String>();
        StringTokenizer entries = new StringTokenizer(pattern, ":,");
        while (entries.hasMoreTokens())
        {
            patterns.add(entries.nextToken());
        }
        
        setPatterns((String[])patterns.toArray());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return array of classpath patterns
     */
    public String[] getPatterns()
    {
        return _patterns;
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
            int startIdx = 0;
            name = name.replace('/','.');
            while(name.startsWith("."))
                ++startIdx;
            if (startIdx > 0)
                name = name.substring(startIdx);

            for (int idx=0; idx<_entries.length; idx++)
            {
                Entry entry = _entries[idx];
                if (entry != null)
                {               
                    if (entry.partial)
                    {
                        if (name.startsWith(entry.classpath))
                        {
                            result = entry.result;
                            break;
                        }
                    }
                    else
                    {
                        if (name.equals(entry.classpath))
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
