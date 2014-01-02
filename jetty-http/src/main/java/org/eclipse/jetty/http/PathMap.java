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

package org.eclipse.jetty.http;

import java.io.Externalizable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.URIUtil;

/* ------------------------------------------------------------ */
/** URI path map to Object.
 * This mapping implements the path specification recommended
 * in the 2.2 Servlet API.
 *
 * Path specifications can be of the following forms:<PRE>
 * /foo/bar           - an exact path specification.
 * /foo/*             - a prefix path specification (must end '/*').
 * *.ext              - a suffix path specification.
 * /                  - the default path specification.
 * </PRE>
 * Matching is performed in the following order <NL>
 * <LI>Exact match.
 * <LI>Longest prefix match.
 * <LI>Longest suffix match.
 * <LI>default.
 * </NL>
 * Multiple path specifications can be mapped by providing a list of
 * specifications. By default this class uses characters ":," as path
 * separators, unless configured differently by calling the static
 * method @see PathMap#setPathSpecSeparators(String)
 * <P>
 * Special characters within paths such as '?� and ';' are not treated specially
 * as it is assumed they would have been either encoded in the original URL or
 * stripped from the path.
 * <P>
 * This class is not synchronized.  If concurrent modifications are
 * possible then it should be synchronized at a higher level.
 *
 *
 */
public class PathMap extends HashMap implements Externalizable
{
    /* ------------------------------------------------------------ */
    private static String __pathSpecSeparators = ":,";

    /* ------------------------------------------------------------ */
    /** Set the path spec separator.
     * Multiple path specification may be included in a single string
     * if they are separated by the characters set in this string.
     * By default this class uses ":," characters as path separators.
     * @param s separators
     */
    public static void setPathSpecSeparators(String s)
    {
        __pathSpecSeparators=s;
    }

    /* --------------------------------------------------------------- */
    final StringMap _prefixMap=new StringMap();
    final StringMap _suffixMap=new StringMap();
    final StringMap _exactMap=new StringMap();

    List _defaultSingletonList=null;
    Entry _prefixDefault=null;
    Entry _default=null;
    final Set _entrySet;
    boolean _nodefault=false;

    /* --------------------------------------------------------------- */
    /** Construct empty PathMap.
     */
    public PathMap()
    {
        super(11);
        _entrySet=entrySet();
    }

    /* --------------------------------------------------------------- */
    /** Construct empty PathMap.
     */
    public PathMap(boolean nodefault)
    {
        super(11);
        _entrySet=entrySet();
        _nodefault=nodefault;
    }

    /* --------------------------------------------------------------- */
    /** Construct empty PathMap.
     */
    public PathMap(int capacity)
    {
        super (capacity);
        _entrySet=entrySet();
    }

    /* --------------------------------------------------------------- */
    /** Construct from dictionary PathMap.
     */
    public PathMap(Map m)
    {
        putAll(m);
        _entrySet=entrySet();
    }

    /* ------------------------------------------------------------ */
    public void writeExternal(java.io.ObjectOutput out)
        throws java.io.IOException
    {
        HashMap map = new HashMap(this);
        out.writeObject(map);
    }

    /* ------------------------------------------------------------ */
    public void readExternal(java.io.ObjectInput in)
        throws java.io.IOException, ClassNotFoundException
    {
        HashMap map = (HashMap)in.readObject();
        this.putAll(map);
    }

    /* --------------------------------------------------------------- */
    /** Add a single path match to the PathMap.
     * @param pathSpec The path specification, or comma separated list of
     * path specifications.
     * @param object The object the path maps to
     */
    @Override
    public Object put(Object pathSpec, Object object)
    {
        String str = pathSpec.toString();
        if ("".equals(str.trim()))
        {          
            Entry entry = new Entry("",object);
            entry.setMapped("");
            _exactMap.put("", entry);
            return super.put("", object);
        }
        
        StringTokenizer tok = new StringTokenizer(str,__pathSpecSeparators);
        Object old =null;

        while (tok.hasMoreTokens())
        {
            String spec=tok.nextToken();

            if (!spec.startsWith("/") && !spec.startsWith("*."))
                throw new IllegalArgumentException("PathSpec "+spec+". must start with '/' or '*.'");

            old = super.put(spec,object);

            // Make entry that was just created.
            Entry entry = new Entry(spec,object);

            if (entry.getKey().equals(spec))
            {
                if (spec.equals("/*"))
                    _prefixDefault=entry;
                else if (spec.endsWith("/*"))
                {
                    String mapped=spec.substring(0,spec.length()-2);
                    entry.setMapped(mapped);
                    _prefixMap.put(mapped,entry);
                    _exactMap.put(mapped,entry);
                    _exactMap.put(spec.substring(0,spec.length()-1),entry);
                }
                else if (spec.startsWith("*."))
                    _suffixMap.put(spec.substring(2),entry);
                else if (spec.equals(URIUtil.SLASH))
                {
                    if (_nodefault)
                        _exactMap.put(spec,entry);
                    else
                    {
                        _default=entry;
                        _defaultSingletonList=
                            Collections.singletonList(_default);
                    }
                }
                else
                {
                    entry.setMapped(spec);
                    _exactMap.put(spec,entry);
                }
            }
        }

        return old;
    }

    /* ------------------------------------------------------------ */
    /** Get object matched by the path.
     * @param path the path.
     * @return Best matched object or null.
     */
    public Object match(String path)
    {
        Map.Entry entry = getMatch(path);
        if (entry!=null)
            return entry.getValue();
        return null;
    }


    /* --------------------------------------------------------------- */
    /** Get the entry mapped by the best specification.
     * @param path the path.
     * @return Map.Entry of the best matched  or null.
     */
    public Entry getMatch(String path)
    {
        Map.Entry entry=null;

        if (path==null)
            return null;

        int l=path.length();
        
        //special case
        if (l == 1 && path.charAt(0)=='/')
        {
            entry = (Map.Entry)_exactMap.get("");
            if (entry != null)
                return (Entry)entry;
        }
        
        // try exact match
        entry=_exactMap.getEntry(path,0,l);
        if (entry!=null)
            return (Entry) entry.getValue();

        // prefix search
        int i=l;
        while((i=path.lastIndexOf('/',i-1))>=0)
        {
            entry=_prefixMap.getEntry(path,0,i);
            if (entry!=null)
                return (Entry) entry.getValue();
        }

        // Prefix Default
        if (_prefixDefault!=null)
            return _prefixDefault;

        // Extension search
        i=0;
        while ((i=path.indexOf('.',i+1))>0)
        {
            entry=_suffixMap.getEntry(path,i+1,l-i-1);
            if (entry!=null)
                return (Entry) entry.getValue();
        }

        // Default
        return _default;
    }

    /* --------------------------------------------------------------- */
    /** Get all entries matched by the path.
     * Best match first.
     * @param path Path to match
     * @return LazyList of Map.Entry instances key=pathSpec
     */
    public Object getLazyMatches(String path)
    {
        Map.Entry entry;
        Object entries=null;

        if (path==null)
            return LazyList.getList(entries);

        int l=path.length();

        // try exact match
        entry=_exactMap.getEntry(path,0,l);
        if (entry!=null)
            entries=LazyList.add(entries,entry.getValue());

        // prefix search
        int i=l-1;
        while((i=path.lastIndexOf('/',i-1))>=0)
        {
            entry=_prefixMap.getEntry(path,0,i);
            if (entry!=null)
                entries=LazyList.add(entries,entry.getValue());
        }

        // Prefix Default
        if (_prefixDefault!=null)
            entries=LazyList.add(entries,_prefixDefault);

        // Extension search
        i=0;
        while ((i=path.indexOf('.',i+1))>0)
        {
            entry=_suffixMap.getEntry(path,i+1,l-i-1);
            if (entry!=null)
                entries=LazyList.add(entries,entry.getValue());
        }

        // Default
        if (_default!=null)
        {
            // Optimization for just the default
            if (entries==null)
                return _defaultSingletonList;

            entries=LazyList.add(entries,_default);
        }

        return entries;
    }

    /* --------------------------------------------------------------- */
    /** Get all entries matched by the path.
     * Best match first.
     * @param path Path to match
     * @return List of Map.Entry instances key=pathSpec
     */
    public List getMatches(String path)
    {
        return LazyList.getList(getLazyMatches(path));
    }

    /* --------------------------------------------------------------- */
    /** Return whether the path matches any entries in the PathMap,
     * excluding the default entry
     * @param path Path to match
     * @return Whether the PathMap contains any entries that match this
     */
    public boolean containsMatch(String path)
    {
    	Entry match = getMatch(path);
    	return match!=null && !match.equals(_default);
    }

    /* --------------------------------------------------------------- */
    @Override
    public Object remove(Object pathSpec)
    {
        if (pathSpec!=null)
        {
            String spec=(String) pathSpec;
            if (spec.equals("/*"))
                _prefixDefault=null;
            else if (spec.endsWith("/*"))
            {
                _prefixMap.remove(spec.substring(0,spec.length()-2));
                _exactMap.remove(spec.substring(0,spec.length()-1));
                _exactMap.remove(spec.substring(0,spec.length()-2));
            }
            else if (spec.startsWith("*."))
                _suffixMap.remove(spec.substring(2));
            else if (spec.equals(URIUtil.SLASH))
            {
                _default=null;
                _defaultSingletonList=null;
            }
            else
                _exactMap.remove(spec);
        }
        return super.remove(pathSpec);
    }

    /* --------------------------------------------------------------- */
    @Override
    public void clear()
    {
        _exactMap.clear();
        _prefixMap.clear();
        _suffixMap.clear();
        _default=null;
        _defaultSingletonList=null;
        super.clear();
    }

    /* --------------------------------------------------------------- */
    /**
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path)
        throws IllegalArgumentException
    {
        return match(pathSpec, path, false);
    }

    /* --------------------------------------------------------------- */
    /**
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path, boolean noDefault)
    throws IllegalArgumentException
    {
        if (pathSpec.length()==0)
            return "/".equals(path);
            
        char c = pathSpec.charAt(0);
        if (c=='/')
        {
            if (!noDefault && pathSpec.length()==1 || pathSpec.equals(path))
                return true;

            if(isPathWildcardMatch(pathSpec, path))
                return true;
        }
        else if (c=='*')
            return path.regionMatches(path.length()-pathSpec.length()+1,
                                      pathSpec,1,pathSpec.length()-1);
        return false;
    }

    /* --------------------------------------------------------------- */
    private static boolean isPathWildcardMatch(String pathSpec, String path)
    {
        // For a spec of "/foo/*" match "/foo" , "/foo/..." but not "/foobar"
        int cpl=pathSpec.length()-2;
        if (pathSpec.endsWith("/*") && path.regionMatches(0,pathSpec,0,cpl))
        {
            if (path.length()==cpl || '/'==path.charAt(cpl))
                return true;
        }
        return false;
    }


    /* --------------------------------------------------------------- */
    /** Return the portion of a path that matches a path spec.
     * @return null if no match at all.
     */
    public static String pathMatch(String pathSpec, String path)
    {
        char c = pathSpec.charAt(0);

        if (c=='/')
        {
            if (pathSpec.length()==1)
                return path;

            if (pathSpec.equals(path))
                return path;

            if (isPathWildcardMatch(pathSpec, path))
                return path.substring(0,pathSpec.length()-2);
        }
        else if (c=='*')
        {
            if (path.regionMatches(path.length()-(pathSpec.length()-1),
                                   pathSpec,1,pathSpec.length()-1))
                return path;
        }
        return null;
    }

    /* --------------------------------------------------------------- */
    /** Return the portion of a path that is after a path spec.
     * @return The path info string
     */
    public static String pathInfo(String pathSpec, String path)
    {
        if ("".equals(pathSpec))
            return path; //servlet 3 spec sec 12.2 will be '/'
        
        char c = pathSpec.charAt(0);

        if (c=='/')
        {
            if (pathSpec.length()==1)
                return null;

            boolean wildcard = isPathWildcardMatch(pathSpec, path);

            // handle the case where pathSpec uses a wildcard and path info is "/*"
            if (pathSpec.equals(path) && !wildcard)
                return null;

            if (wildcard)
            {
                if (path.length()==pathSpec.length()-2)
                    return null;
                return path.substring(pathSpec.length()-2);
            }
        }
        return null;
    }


    /* ------------------------------------------------------------ */
    /** Relative path.
     * @param base The base the path is relative to.
     * @param pathSpec The spec of the path segment to ignore.
     * @param path the additional path
     * @return base plus path with pathspec removed
     */
    public static String relativePath(String base,
                                      String pathSpec,
                                      String path )
    {
        String info=pathInfo(pathSpec,path);
        if (info==null)
            info=path;

        if( info.startsWith( "./"))
            info = info.substring( 2);
        if( base.endsWith( URIUtil.SLASH))
            if( info.startsWith( URIUtil.SLASH))
                path = base + info.substring(1);
            else
                path = base + info;
        else
            if( info.startsWith( URIUtil.SLASH))
                path = base + info;
            else
                path = base + URIUtil.SLASH + info;
        return path;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class Entry implements Map.Entry
    {
        private final Object key;
        private final Object value;
        private String mapped;
        private transient String string;

        Entry(Object key, Object value)
        {
            this.key=key;
            this.value=value;
        }

        public Object getKey()
        {
            return key;
        }

        public Object getValue()
        {
            return value;
        }

        public Object setValue(Object o)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString()
        {
            if (string==null)
                string=key+"="+value;
            return string;
        }

        public String getMapped()
        {
            return mapped;
        }

        void setMapped(String mapped)
        {
            this.mapped = mapped;
        }
    }
}
