//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.pathmap;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Path Mappings of PathSpec to Resource.
 * <p>
 * Sorted into search order upon entry into the Set
 *
 * @param <E> the type of mapping endpoint
 */
@ManagedObject("Path Mappings")
public class PathMappings<E> extends AbstractMap<PathSpec, E> implements Iterable<MappedResource<E>>, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(PathMappings.class);

    private final Set<MappedResource<E>> _mappings = new TreeSet<>(Map.Entry.comparingByKey());

    /**
     * When _orderIsSignificant is true, the order of the MappedResources is significant and a match needs to be iteratively
     * tried against each mapping (ordered by group then add order) to find the first that matches.
     */
    private boolean _orderIsSignificant;
    private boolean _optimizedExact = true;
    private final Map<String, MappedResource<E>> _exactMap = new HashMap<>();
    private boolean _optimizedPrefix = true;
    private final Index.Mutable<MappedResource<E>> _prefixMap = new Index.Builder<MappedResource<E>>()
        .caseSensitive(true)
        .mutable()
        .build();
    private boolean _optimizedSuffix = true;
    private final Index.Mutable<MappedResource<E>> _suffixMap = new Index.Builder<MappedResource<E>>()
        .caseSensitive(true)
        .mutable()
        .build();

    private MappedResource<E> _servletRoot;
    private MappedResource<E> _servletDefault;

    @Override
    public Set<Entry<PathSpec, E>> entrySet()
    {
        @SuppressWarnings("unchecked")
        Set<Map.Entry<PathSpec, E>> entries = (Set<Map.Entry<PathSpec, E>>)(Set<? extends Map.Entry<PathSpec, E>>)_mappings;
        return entries;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, toString(), _mappings);
    }

    @ManagedAttribute(value = "mappings", readonly = true)
    public List<MappedResource<E>> getMappings()
    {
        return new ArrayList<>(_mappings);
    }

    public int size()
    {
        return _mappings.size();
    }

    public void reset()
    {
        _mappings.clear();
        _prefixMap.clear();
        _suffixMap.clear();
        _optimizedExact = true;
        _optimizedPrefix = true;
        _optimizedSuffix = true;
        _orderIsSignificant = false;
        _servletRoot = null;
        _servletDefault = null;
    }

    public Stream<MappedResource<E>> streamResources()
    {
        return _mappings.stream();
    }

    public boolean removeIf(Predicate<MappedResource<E>> predicate)
    {
        return _mappings.removeIf(predicate);
    }

    /**
     * Return a list of MatchedResource matches for the specified path.
     *
     * @param path the path to return matches on
     * @return the list of mapped resource the path matches on
     */
    public List<MatchedResource<E>> getMatchedList(String path)
    {
        List<MatchedResource<E>> ret = new ArrayList<>();
        for (MappedResource<E> mr : _mappings)
        {
            MatchedPath matchedPath = mr.getPathSpec().matched(path);
            if (matchedPath != null)
            {
                ret.add(new MatchedResource<>(mr.getResource(), mr.getPathSpec(), matchedPath));
            }
        }
        return ret;
    }

    /**
     * Return a list of MappedResource matches for the specified path.
     *
     * @param path the path to return matches on
     * @return the list of mapped resource the path matches on
     */
    public List<MappedResource<E>> getMatches(String path)
    {
        if (_mappings.isEmpty())
            return Collections.emptyList();

        boolean isRootPath = "/".equals(path);

        // Iterator over all the mapping, adding only those that match.
        List<MappedResource<E>> matches = null;
        for (MappedResource<E> mr : _mappings)
        {
            switch (mr.getPathSpec().getGroup())
            {
                case ROOT:
                    if (isRootPath)
                    {
                        if (matches == null)
                            matches = new ArrayList<>();
                        matches.add(mr);
                    }
                    break;
                case DEFAULT:
                    if (isRootPath || mr.getPathSpec().matched(path) != null)
                    {
                        if (matches == null)
                            matches = new ArrayList<>();
                        matches.add(mr);
                    }
                    break;
                default:
                    if (mr.getPathSpec().matched(path) != null)
                    {
                        if (matches == null)
                            matches = new ArrayList<>();
                        matches.add(mr);
                    }
                    break;
            }
        }
        return matches == null ? Collections.emptyList() : matches;
    }

    /**
     * <p>Find the best single match for a path.</p>
     * <p>The match may be found by optimized direct lookups when possible, otherwise all mappings
     * are iterated over and the first match returned</p>
     * @param path The path to match
     * @return A {@link MatchedResource} instance or null if no mappings matched.
     * @see #getMatchedIteratively(String)
     */
    public MatchedResource<E> getMatched(String path)
    {
        if (_mappings.isEmpty())
            return null;

        // If order is significant, then we need to match by iterating over all mappings.
        if (_orderIsSignificant)
            return getMatchedIteratively(path);

        // Otherwise, we can try optimized matches against each group

        // Try a root match
        if (_servletRoot != null && "/".equals(path))
            return _servletRoot.getPreMatched();

        // try an exact match
        MappedResource<E> exact = _exactMap.get(path);
        if (exact != null)
            return exact.getPreMatched();

        // Try a prefix match
        MappedResource<E> prefix = _prefixMap.getBest(path);
        if (prefix != null)
        {
            MatchedPath matchedPath = prefix.getPathSpec().matched(path);
            if (matchedPath != null)
                return new MatchedResource<>(prefix.getResource(), prefix.getPathSpec(), matchedPath);
        }

        // Try a suffix match
        if (!_suffixMap.isEmpty())
        {
            int i = Math.max(0, path.lastIndexOf("/"));
            // Loop through each suffix mark
            // Input is "/a.b.c.foo"
            //  Loop 1: "b.c.foo"
            //  Loop 2: "c.foo"
            //  Loop 3: "foo"
            while ((i = path.indexOf('.', i + 1)) > 0)
            {
                prefix = _suffixMap.get(path, i + 1, path.length() - i - 1);
                if (prefix == null)
                    continue;

                MatchedPath matchedPath = prefix.getPathSpec().matched(path);
                if (matchedPath != null)
                    return new MatchedResource<>(prefix.getResource(), prefix.getPathSpec(), matchedPath);
            }
        }

        if (_servletDefault != null)
            return new MatchedResource<>(_servletDefault.getResource(), _servletDefault.getPathSpec(), _servletDefault.getPathSpec().matched(path));

        return null;
    }

    /**
     * <p>Iterate over all mappings, returning the first that matches.</p>
     * @param path The path to match.
     * @return A {@link MatchedResource} instance or null if no mappings matched.
     * @see #getMatched(String)
     */
    private MatchedResource<E> getMatchedIteratively(String path)
    {
        MatchedPath matchedPath;
        PathSpecGroup lastGroup = null;

        boolean skipRestOfGroup = false;
        // Search all the mappings
        for (MappedResource<E> mr : _mappings)
        {
            PathSpecGroup group = mr.getPathSpec().getGroup();
            if (group == lastGroup && skipRestOfGroup)
            {
                continue; // skip
            }

            // Run servlet spec optimizations on first hit of specific groups
            if (group != lastGroup)
            {
                // New group, reset skip logic
                skipRestOfGroup = false;

                // New group in list, so let's look for an optimization
                switch (group)
                {
                    case EXACT:
                    {
                        if (_optimizedExact)
                        {
                            MappedResource<E> exact = _exactMap.get(path);
                            if (exact != null)
                                return exact.getPreMatched();
                            // If we reached here, there's NO optimized EXACT Match possible, skip simple match below
                            skipRestOfGroup = true;
                        }
                        break;
                    }

                    case PREFIX_GLOB:
                    {
                        if (_optimizedPrefix)
                        {
                            MappedResource<E> candidate = _prefixMap.getBest(path);
                            if (candidate != null)
                            {
                                matchedPath = candidate.getPathSpec().matched(path);
                                if (matchedPath != null)
                                    return new MatchedResource<>(candidate.getResource(), candidate.getPathSpec(), matchedPath);
                            }

                            // If we reached here, there's NO optimized PREFIX Match possible, skip simple match below
                            skipRestOfGroup = true;
                        }
                        break;
                    }

                    case SUFFIX_GLOB:
                    {
                        if (_optimizedSuffix)
                        {
                            int i = 0;
                            // Loop through each suffix mark
                            // Input is "/a.b.c.foo"
                            //  Loop 1: "b.c.foo"
                            //  Loop 2: "c.foo"
                            //  Loop 3: "foo"
                            while ((i = path.indexOf('.', i + 1)) > 0)
                            {
                                MappedResource<E> candidate = _suffixMap.get(path, i + 1, path.length() - i - 1);
                                if (candidate == null)
                                    continue;

                                matchedPath = candidate.getPathSpec().matched(path);
                                if (matchedPath != null)
                                    return new MatchedResource<>(candidate.getResource(), candidate.getPathSpec(), matchedPath);
                            }
                            // If we reached here, there's NO optimized SUFFIX Match possible, skip simple match below
                            skipRestOfGroup = true;
                        }
                        break;
                    }

                    default:
                }
            }

            matchedPath = mr.getPathSpec().matched(path);
            if (matchedPath != null)
                return new MatchedResource<>(mr.getResource(), mr.getPathSpec(), matchedPath);

            lastGroup = group;
        }

        return null;
    }

    @Override
    public Iterator<MappedResource<E>> iterator()
    {
        return _mappings.iterator();
    }

    @Override
    public E get(Object key)
    {
        return key instanceof PathSpec pathSpec ? get(pathSpec) : null;
    }

    public E get(PathSpec pathSpec)
    {
        if (pathSpec == null)
            return null;

        for (MappedResource<E> mr : _mappings)
        {
            if (pathSpec.equals(mr.getKey()))
                return mr.getValue();
        }
        return null;
    }

    public E put(String pathSpecString, E resource)
    {
        return put(PathSpec.from(pathSpecString), resource);
    }

    @Override
    public E put(PathSpec pathSpec, E resource)
    {
        E old = remove(pathSpec);
        MappedResource<E> entry = new MappedResource<>(pathSpec, resource);
        _mappings.add(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("Added {} replacing {} to {}", entry, old, this);

        switch (pathSpec.getGroup())
        {
            case EXACT:
                if (pathSpec instanceof ServletPathSpec)
                {
                    String exact = pathSpec.getDeclaration();
                    if (exact != null)
                        _exactMap.put(exact, entry);
                }
                else
                {
                    // This is not a Servlet mapping, turn off optimization on Exact
                    // TODO: see if we can optimize all Regex / UriTemplate versions here too.
                    // Note: Example exact in Regex that can cause problems `^/a\Q/b\E/` (which is only ever matching `/a/b/`)
                    // Note: UriTemplate can handle exact easily enough
                    _optimizedExact = false;
                    _orderIsSignificant = true;
                }
                break;
            case PREFIX_GLOB:
                if (pathSpec instanceof ServletPathSpec)
                {
                    String prefix = pathSpec.getPrefix();
                    if (prefix != null)
                        _prefixMap.put(prefix, entry);
                }
                else
                {
                    // This is not a Servlet mapping, turn off optimization on Prefix
                    // TODO: see if we can optimize all Regex / UriTemplate versions here too.
                    // Note: Example Prefix in Regex that can cause problems `^/a/b+` or `^/a/bb*` ('b' one or more times)
                    // Note: Example Prefix in UriTemplate that might cause problems `/a/{b}/{c}`
                    _optimizedPrefix = false;
                    _orderIsSignificant = true;
                }
                break;
            case SUFFIX_GLOB:
                if (pathSpec instanceof ServletPathSpec)
                {
                    String suffix = pathSpec.getSuffix();
                    if (suffix != null)
                        _suffixMap.put(suffix, entry);
                }
                else
                {
                    // This is not a Servlet mapping, turn off optimization on Suffix
                    // TODO: see if we can optimize all Regex / UriTemplate versions here too.
                    // Note: Example suffix in Regex that can cause problems `^.*/path/name.ext` or `^/a/.*(ending)`
                    // Note: Example suffix in UriTemplate that can cause problems `/{a}/name.ext`
                    _optimizedSuffix = false;
                    _orderIsSignificant = true;
                }
                break;
            case ROOT:
                if (pathSpec instanceof ServletPathSpec)
                {
                    if (_servletRoot == null)
                        _servletRoot = entry;
                }
                else
                {
                    _orderIsSignificant = true;
                }
                break;
            case DEFAULT:
                if (pathSpec instanceof ServletPathSpec)
                {
                    if (_servletDefault == null)
                        _servletDefault = entry;
                }
                else
                {
                    _orderIsSignificant = true;
                }
                break;
            default:
        }

        return old;
    }

    @Override
    public E remove(Object key)
    {
        return key instanceof PathSpec pathSpec ? remove(pathSpec) : null;
    }

    public E remove(PathSpec pathSpec)
    {
        Iterator<MappedResource<E>> iter = _mappings.iterator();
        E removed = null;
        while (iter.hasNext())
        {
            MappedResource<E> entry = iter.next();
            if (entry.getPathSpec().equals(pathSpec))
            {
                removed = entry.getResource();
                iter.remove();
                break;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Removed {} at {} from {}", removed, pathSpec, this);

        if (removed != null)
        {
            switch (pathSpec.getGroup())
            {
                case EXACT:
                    String exact = pathSpec.getDeclaration();
                    if (exact != null)
                    {
                        _exactMap.remove(exact);
                        // Recalculate _optimizeExact
                        _optimizedExact = canBeOptimized(PathSpecGroup.EXACT);
                        _orderIsSignificant = nonServletPathSpec();
                    }
                    break;
                case PREFIX_GLOB:
                    String prefix = pathSpec.getPrefix();
                    if (prefix != null)
                    {
                        _prefixMap.remove(prefix);
                        // Recalculate _optimizePrefix
                        _optimizedPrefix = canBeOptimized(PathSpecGroup.PREFIX_GLOB);
                        _orderIsSignificant = nonServletPathSpec();
                    }
                    break;
                case SUFFIX_GLOB:
                    String suffix = pathSpec.getSuffix();
                    if (suffix != null)
                    {
                        _suffixMap.remove(suffix);
                        // Recalculate _optimizeSuffix
                        _optimizedSuffix = canBeOptimized(PathSpecGroup.SUFFIX_GLOB);
                        _orderIsSignificant = nonServletPathSpec();
                    }
                    break;
                case ROOT:
                    _servletRoot = _mappings.stream()
                        .filter(mapping -> mapping.getPathSpec().getGroup() == PathSpecGroup.ROOT)
                        .filter(mapping -> mapping.getPathSpec() instanceof ServletPathSpec)
                        .findFirst().orElse(null);
                    _orderIsSignificant = nonServletPathSpec();
                    break;
                case DEFAULT:
                    _servletDefault = _mappings.stream()
                        .filter(mapping -> mapping.getPathSpec().getGroup() == PathSpecGroup.DEFAULT)
                        .filter(mapping -> mapping.getPathSpec() instanceof ServletPathSpec)
                        .findFirst().orElse(null);
                    _orderIsSignificant = nonServletPathSpec();
                    break;
            }
        }

        return removed;
    }

    private boolean canBeOptimized(PathSpecGroup suffixGlob)
    {
        return _mappings.stream()
            .filter((mapping) -> mapping.getPathSpec().getGroup() == suffixGlob)
            .allMatch((mapping) -> mapping.getPathSpec() instanceof ServletPathSpec);
    }

    private boolean nonServletPathSpec()
    {
        return _mappings.stream()
            .allMatch((mapping) -> mapping.getPathSpec() instanceof ServletPathSpec);
    }

    @Override
    public String toString()
    {
        return String.format("%s[size=%d]", this.getClass().getSimpleName(), _mappings.size());
    }
}
