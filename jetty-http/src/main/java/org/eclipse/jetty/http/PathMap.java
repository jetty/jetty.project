//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.Predicate;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.URIUtil;

/**
 * URI path map to Object.
 * <p>
 * This mapping implements the path specification recommended
 * in the 2.2 Servlet API.
 * </p>
 *
 * <p>
 * Path specifications can be of the following forms:
 * </p>
 * <pre>
 * /foo/bar           - an exact path specification.
 * /foo/*             - a prefix path specification (must end '/*').
 * *.ext              - a suffix path specification.
 * /                  - the default path specification.
 * ""                 - the / path specification
 * </pre>
 *
 * Matching is performed in the following order
 * <ol>
 * <li>Exact match.</li>
 * <li>Longest prefix match.</li>
 * <li>Longest suffix match.</li>
 * <li>default.</li>
 * </ol>
 *
 * <p>
 * Multiple path specifications can be mapped by providing a list of
 * specifications. By default this class uses characters ":," as path
 * separators, unless configured differently by calling the static
 * method @see PathMap#setPathSpecSeparators(String)
 * <p>
 * Special characters within paths such as '?� and ';' are not treated specially
 * as it is assumed they would have been either encoded in the original URL or
 * stripped from the path.
 * <p>
 * This class is not synchronized.  If concurrent modifications are
 * possible then it should be synchronized at a higher level.
 *
 * @param <O> the Map.Entry value type
 * @deprecated replaced with {@link org.eclipse.jetty.http.pathmap.PathMappings} (this class will be removed in Jetty 10)
 */
@Deprecated
public class PathMap<O> extends HashMap<String, O>
{

    private static String __pathSpecSeparators = ":,";

    /**
     * Set the path spec separator.
     * Multiple path specification may be included in a single string
     * if they are separated by the characters set in this string.
     * By default this class uses ":," characters as path separators.
     *
     * @param s separators
     */
    public static void setPathSpecSeparators(String s)
    {
        __pathSpecSeparators = s;
    }

    Trie<MappedEntry<O>> _prefixMap = new ArrayTernaryTrie<>(false);
    Trie<MappedEntry<O>> _suffixMap = new ArrayTernaryTrie<>(false);
    final Map<String, MappedEntry<O>> _exactMap = new HashMap<>();

    List<MappedEntry<O>> _defaultSingletonList = null;
    MappedEntry<O> _prefixDefault = null;
    MappedEntry<O> _default = null;
    boolean _nodefault = false;

    public PathMap()
    {
        this(11);
    }

    public PathMap(boolean noDefault)
    {
        this(11, noDefault);
    }

    public PathMap(int capacity)
    {
        this(capacity, false);
    }

    private PathMap(int capacity, boolean noDefault)
    {
        super(capacity);
        _nodefault = noDefault;
    }

    /**
     * Construct from dictionary PathMap.
     *
     * @param dictMap the map representing the dictionary to build this PathMap from
     */
    public PathMap(Map<String, ? extends O> dictMap)
    {
        putAll(dictMap);
    }

    /**
     * Add a single path match to the PathMap.
     *
     * @param pathSpec The path specification, or comma separated list of
     * path specifications.
     * @param object The object the path maps to
     */
    @Override
    public O put(String pathSpec, O object)
    {
        if ("".equals(pathSpec.trim()))
        {
            MappedEntry<O> entry = new MappedEntry<>("", object);
            entry.setMapped("");
            _exactMap.put("", entry);
            return super.put("", object);
        }

        StringTokenizer tok = new StringTokenizer(pathSpec, __pathSpecSeparators);
        O old = null;

        while (tok.hasMoreTokens())
        {
            String spec = tok.nextToken();

            if (!spec.startsWith("/") && !spec.startsWith("*."))
                throw new IllegalArgumentException("PathSpec " + spec + ". must start with '/' or '*.'");

            old = super.put(spec, object);

            // Make entry that was just created.
            MappedEntry<O> entry = new MappedEntry<>(spec, object);

            if (entry.getKey().equals(spec))
            {
                if (spec.equals("/*"))
                    _prefixDefault = entry;
                else if (spec.endsWith("/*"))
                {
                    String mapped = spec.substring(0, spec.length() - 2);
                    entry.setMapped(mapped);
                    while (!_prefixMap.put(mapped, entry))
                    {
                        _prefixMap = new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedEntry<O>>)_prefixMap, 1.5);
                    }
                }
                else if (spec.startsWith("*."))
                {
                    String suffix = spec.substring(2);
                    while (!_suffixMap.put(suffix, entry))
                    {
                        _suffixMap = new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedEntry<O>>)_suffixMap, 1.5);
                    }
                }
                else if (spec.equals(URIUtil.SLASH))
                {
                    if (_nodefault)
                        _exactMap.put(spec, entry);
                    else
                    {
                        _default = entry;
                        _defaultSingletonList = Collections.singletonList(_default);
                    }
                }
                else
                {
                    entry.setMapped(spec);
                    _exactMap.put(spec, entry);
                }
            }
        }

        return old;
    }

    /**
     * Get object matched by the path.
     *
     * @param path the path.
     * @return Best matched object or null.
     */
    public O match(String path)
    {
        MappedEntry<O> entry = getMatch(path);
        if (entry != null)
            return entry.getValue();
        return null;
    }

    /**
     * Get the entry mapped by the best specification.
     *
     * @param path the path.
     * @return Map.Entry of the best matched  or null.
     */
    public MappedEntry<O> getMatch(String path)
    {
        if (path == null)
            return null;

        int l = path.length();

        MappedEntry<O> entry = null;

        //special case
        if (l == 1 && path.charAt(0) == '/')
        {
            entry = _exactMap.get("");
            if (entry != null)
                return entry;
        }

        // try exact match
        entry = _exactMap.get(path);
        if (entry != null)
            return entry;

        // prefix search
        int i = l;
        final Trie<PathMap.MappedEntry<O>> prefix_map = _prefixMap;
        while (i >= 0)
        {
            entry = prefix_map.getBest(path, 0, i);
            if (entry == null)
                break;
            String key = entry.getKey();
            if (key.length() - 2 >= path.length() || path.charAt(key.length() - 2) == '/')
                return entry;
            i = key.length() - 3;
        }

        // Prefix Default
        if (_prefixDefault != null)
            return _prefixDefault;

        // Extension search
        i = 0;
        final Trie<PathMap.MappedEntry<O>> suffix_map = _suffixMap;
        while ((i = path.indexOf('.', i + 1)) > 0)
        {
            entry = suffix_map.get(path, i + 1, l - i - 1);
            if (entry != null)
                return entry;
        }

        // Default
        return _default;
    }

    /**
     * Get all entries matched by the path.
     * Best match first.
     *
     * @param path Path to match
     * @return List of Map.Entry instances key=pathSpec
     */
    public List<? extends Map.Entry<String, O>> getMatches(String path)
    {
        MappedEntry<O> entry;
        List<MappedEntry<O>> entries = new ArrayList<>();

        if (path == null)
            return entries;
        if (path.isEmpty())
            return _defaultSingletonList;

        // try exact match
        entry = _exactMap.get(path);
        if (entry != null)
            entries.add(entry);

        // prefix search
        int l = path.length();
        int i = l;
        final Trie<PathMap.MappedEntry<O>> prefix_map = _prefixMap;
        while (i >= 0)
        {
            entry = prefix_map.getBest(path, 0, i);
            if (entry == null)
                break;
            String key = entry.getKey();
            if (key.length() - 2 >= path.length() || path.charAt(key.length() - 2) == '/')
                entries.add(entry);

            i = key.length() - 3;
        }

        // Prefix Default
        if (_prefixDefault != null)
            entries.add(_prefixDefault);

        // Extension search
        i = 0;
        final Trie<PathMap.MappedEntry<O>> suffix_map = _suffixMap;
        while ((i = path.indexOf('.', i + 1)) > 0)
        {
            entry = suffix_map.get(path, i + 1, l - i - 1);
            if (entry != null)
                entries.add(entry);
        }

        // root match
        if ("/".equals(path))
        {
            entry = _exactMap.get("");
            if (entry != null)
                entries.add(entry);
        }

        // Default
        if (_default != null)
            entries.add(_default);

        return entries;
    }

    /**
     * Return whether the path matches any entries in the PathMap,
     * excluding the default entry
     *
     * @param path Path to match
     * @return Whether the PathMap contains any entries that match this
     */
    public boolean containsMatch(String path)
    {
        MappedEntry<?> match = getMatch(path);
        return match != null && !match.equals(_default);
    }

    @Override
    public O remove(Object pathSpec)
    {
        if (pathSpec != null)
        {
            String spec = (String)pathSpec;
            if (spec.equals("/*"))
                _prefixDefault = null;
            else if (spec.endsWith("/*"))
                _prefixMap.remove(spec.substring(0, spec.length() - 2));
            else if (spec.startsWith("*."))
                _suffixMap.remove(spec.substring(2));
            else if (spec.equals(URIUtil.SLASH))
            {
                _default = null;
                _defaultSingletonList = null;
            }
            else
                _exactMap.remove(spec);
        }
        return super.remove(pathSpec);
    }

    @Override
    public void clear()
    {
        _exactMap.clear();
        _prefixMap = new ArrayTernaryTrie<>(false);
        _suffixMap = new ArrayTernaryTrie<>(false);
        _default = null;
        _defaultSingletonList = null;
        _prefixDefault = null;
        super.clear();
    }

    /**
     * @param pathSpec the path spec
     * @param path the path
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path)
    {
        return match(pathSpec, path, false);
    }

    /**
     * @param pathSpec the path spec
     * @param path the path
     * @param noDefault true to not handle the default path "/" special, false to allow matcher rules to run
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path, boolean noDefault)
    {
        if (pathSpec.isEmpty())
            return "/".equals(path);

        char c = pathSpec.charAt(0);
        if (c == '/')
        {
            if (!noDefault && pathSpec.length() == 1 || pathSpec.equals(path))
                return true;

            return isPathWildcardMatch(pathSpec, path);
        }
        else if (c == '*')
            return path.regionMatches(path.length() - pathSpec.length() + 1,
                pathSpec, 1, pathSpec.length() - 1);
        return false;
    }

    private static boolean isPathWildcardMatch(String pathSpec, String path)
    {
        // For a spec of "/foo/*" match "/foo" , "/foo/..." but not "/foobar"
        int cpl = pathSpec.length() - 2;
        if (pathSpec.endsWith("/*") && path.regionMatches(0, pathSpec, 0, cpl))
        {
            return path.length() == cpl || '/' == path.charAt(cpl);
        }
        return false;
    }

    /**
     * Return the portion of a path that matches a path spec.
     *
     * @param pathSpec the path spec
     * @param path the path
     * @return null if no match at all.
     */
    public static String pathMatch(String pathSpec, String path)
    {
        char c = pathSpec.charAt(0);

        if (c == '/')
        {
            if (pathSpec.length() == 1)
                return path;

            if (pathSpec.equals(path))
                return path;

            if (isPathWildcardMatch(pathSpec, path))
                return path.substring(0, pathSpec.length() - 2);
        }
        else if (c == '*')
        {
            if (path.regionMatches(path.length() - (pathSpec.length() - 1),
                pathSpec, 1, pathSpec.length() - 1))
                return path;
        }
        return null;
    }

    /**
     * Return the portion of a path that is after a path spec.
     *
     * @param pathSpec the path spec
     * @param path the path
     * @return The path info string
     */
    public static String pathInfo(String pathSpec, String path)
    {
        if ("".equals(pathSpec))
            return path; //servlet 3 spec sec 12.2 will be '/'

        char c = pathSpec.charAt(0);

        if (c == '/')
        {
            if (pathSpec.length() == 1)
                return null;

            boolean wildcard = isPathWildcardMatch(pathSpec, path);

            // handle the case where pathSpec uses a wildcard and path info is "/*"
            if (pathSpec.equals(path) && !wildcard)
                return null;

            if (wildcard)
            {
                if (path.length() == pathSpec.length() - 2)
                    return null;
                return path.substring(pathSpec.length() - 2);
            }
        }
        return null;
    }

    /**
     * Relative path.
     *
     * @param base The base the path is relative to.
     * @param pathSpec The spec of the path segment to ignore.
     * @param path the additional path
     * @return base plus path with pathspec removed
     */
    public static String relativePath(String base,
                                      String pathSpec,
                                      String path)
    {
        String info = pathInfo(pathSpec, path);
        if (info == null)
            info = path;

        if (info.startsWith("./"))
            info = info.substring(2);
        if (base.endsWith(URIUtil.SLASH))
            if (info.startsWith(URIUtil.SLASH))
                path = base + info.substring(1);
            else
                path = base + info;
        else if (info.startsWith(URIUtil.SLASH))
            path = base + info;
        else
            path = base + URIUtil.SLASH + info;
        return path;
    }

    public static class MappedEntry<O> implements Map.Entry<String, O>
    {
        private final String key;
        private final O value;
        private String mapped;

        MappedEntry(String key, O value)
        {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey()
        {
            return key;
        }

        @Override
        public O getValue()
        {
            return value;
        }

        @Override
        public O setValue(O o)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString()
        {
            return key + "=" + value;
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

    public static class PathSet extends AbstractSet<String> implements Predicate<String>
    {
        private final PathMap<Boolean> _map = new PathMap<>();

        @Override
        public Iterator<String> iterator()
        {
            return _map.keySet().iterator();
        }

        @Override
        public int size()
        {
            return _map.size();
        }

        @Override
        public boolean add(String item)
        {
            return _map.put(item, Boolean.TRUE) == null;
        }

        @Override
        public boolean remove(Object item)
        {
            return _map.remove(item) != null;
        }

        @Override
        public boolean contains(Object o)
        {
            return _map.containsKey(o);
        }

        @Override
        public boolean test(String s)
        {
            return _map.containsMatch(s);
        }

        public boolean containsMatch(String s)
        {
            return _map.containsMatch(s);
        }
    }
}
