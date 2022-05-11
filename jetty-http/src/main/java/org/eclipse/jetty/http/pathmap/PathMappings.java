//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.pathmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Path Mappings of PathSpec to Resource.
 * <p>
 * Sorted into search order upon entry into the Set
 *
 * @param <E> the type of mapping endpoint
 */
@ManagedObject("Path Mappings")
public class PathMappings<E> implements Iterable<MappedResource<E>>, Dumpable
{
    private static final Logger LOG = Log.getLogger(PathMappings.class);
    private final Set<MappedResource<E>> _mappings = new TreeSet<>(Comparator.comparing(MappedResource::getPathSpec));

    private boolean _optimizedExact = true;
    private Trie<MappedResource<E>> _exactMap = new ArrayTernaryTrie<>(false);
    private boolean _optimizedPrefix = true;
    private Trie<MappedResource<E>> _prefixMap = new ArrayTernaryTrie<>(false);
    private boolean _optimizedSuffix = true;
    private Trie<MappedResource<E>> _suffixMap = new ArrayTernaryTrie<>(false);

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
    }

    public void removeIf(Predicate<MappedResource<E>> predicate)
    {
        _mappings.removeIf(predicate);
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
        boolean isRootPath = "/".equals(path);

        List<MappedResource<E>> ret = new ArrayList<>();
        for (MappedResource<E> mr : _mappings)
        {
            switch (mr.getPathSpec().getGroup())
            {
                case ROOT:
                    if (isRootPath)
                        ret.add(mr);
                    break;
                case DEFAULT:
                    if (isRootPath || mr.getPathSpec().matched(path) != null)
                        ret.add(mr);
                    break;
                default:
                    if (mr.getPathSpec().matched(path) != null)
                        ret.add(mr);
                    break;
            }
        }
        return ret;
    }

    public MatchedResource<E> getMatched(String path)
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
                // New group in list, so let's look for an optimization
                switch (group)
                {
                    case EXACT:
                    {
                        if (_optimizedExact)
                        {
                            int i = path.length();

                            final Trie<MappedResource<E>> exact_map = _exactMap;
                            while (i >= 0)
                            {
                                MappedResource<E> candidate = exact_map.getBest(path, 0, i);
                                if (candidate == null)
                                    break;

                                matchedPath = candidate.getPathSpec().matched(path);
                                if (matchedPath != null)
                                {
                                    return new MatchedResource<>(candidate.getResource(), candidate.getPathSpec(), matchedPath);
                                }
                                i--;
                            }
                            // If we reached here, there's NO optimized EXACT Match possible, skip simple match below
                            skipRestOfGroup = true;
                        }
                        break;
                    }

                    case PREFIX_GLOB:
                    {
                        if (_optimizedPrefix)
                        {
                            int i = path.length();
                            final Trie<MappedResource<E>> prefix_map = _prefixMap;
                            while (i >= 0)
                            {
                                MappedResource<E> candidate = prefix_map.getBest(path, 0, i);
                                if (candidate == null)
                                    break;

                                matchedPath = candidate.getPathSpec().matched(path);
                                if (matchedPath != null)
                                    return new MatchedResource<>(candidate.getResource(), candidate.getPathSpec(), matchedPath);
                                i--;
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
                            final Trie<MappedResource<E>> suffix_map = _suffixMap;
                            while ((i = path.indexOf('.', i + 1)) > 0)
                            {
                                MappedResource<E> candidate = suffix_map.get(path, i + 1, path.length() - i - 1);
                                if (candidate == null)
                                    break;

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

    /**
     * @deprecated use {@link #getMatched(String)} instead
     */
    @Deprecated
    public MappedResource<E> getMatch(String path)
    {
        throw new UnsupportedOperationException("Use .getMatched(String) instead");
    }

    @Override
    public Iterator<MappedResource<E>> iterator()
    {
        return _mappings.iterator();
    }

    /**
     * @deprecated use {@link PathSpec#from(String)} instead
     */
    @Deprecated
    public static PathSpec asPathSpec(String pathSpecString)
    {
        return PathSpec.from(pathSpecString);
    }

    public E get(PathSpec spec)
    {
        return _mappings.stream()
            .filter(mappedResource -> mappedResource.getPathSpec().equals(spec))
            .map(MappedResource::getResource)
            .findFirst()
            .orElse(null);
    }

    public boolean put(String pathSpecString, E resource)
    {
        return put(PathSpec.from(pathSpecString), resource);
    }

    public boolean put(PathSpec pathSpec, E resource)
    {
        MappedResource<E> entry = new MappedResource<>(pathSpec, resource);
        switch (pathSpec.getGroup())
        {
            case EXACT:
                if (pathSpec instanceof ServletPathSpec)
                {
                    String exact = pathSpec.getDeclaration();
                    while (exact != null && !_exactMap.put(exact, entry))
                    {
                        _exactMap = new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedResource<E>>)_exactMap, 1.5);
                    }
                }
                else
                {
                    // This is not a Servlet mapping, turn off optimization on Exact
                    _optimizedExact = false;
                }
                break;
            case PREFIX_GLOB:
                if (pathSpec instanceof ServletPathSpec)
                {
                    String prefix = pathSpec.getPrefix();
                    while (prefix != null && !_prefixMap.put(prefix, entry))
                    {
                        _prefixMap = new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedResource<E>>)_prefixMap, 1.5);
                    }
                }
                else
                {
                    // This is not a Servlet mapping, turn off optimization on Prefix
                    _optimizedPrefix = false;
                }
                break;
            case SUFFIX_GLOB:
                if (pathSpec instanceof ServletPathSpec)
                {
                    String suffix = pathSpec.getSuffix();
                    while (suffix != null && !_suffixMap.put(suffix, entry))
                    {
                        _suffixMap = new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedResource<E>>)_prefixMap, 1.5);
                    }
                }
                else
                {
                    // This is not a Servlet mapping, turn off optimization on Suffix
                    _optimizedSuffix = false;
                }
                break;
            default:
        }

        boolean added = _mappings.add(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} to {}", added ? "Added" : "Ignored", entry, this);
        return added;
    }

    @SuppressWarnings("incomplete-switch")
    public boolean remove(PathSpec pathSpec)
    {
        switch (pathSpec.getGroup())
        {
            case EXACT:
                String exact = pathSpec.getDeclaration();
                if (exact != null)
                {
                    _exactMap.remove(exact);
                    // TODO: recalculate _optimizeExact
                }
                break;
            case PREFIX_GLOB:
                String prefix = pathSpec.getPrefix();
                if (prefix != null)
                {
                    _prefixMap.remove(prefix);
                    // TODO: recalculate _optimizePrefix
                }
                break;
            case SUFFIX_GLOB:
                String suffix = pathSpec.getSuffix();
                if (suffix != null)
                {
                    _suffixMap.remove(suffix);
                    // TODO: recalculate _optimizeSuffix
                }
                break;
        }

        Iterator<MappedResource<E>> iter = _mappings.iterator();
        boolean removed = false;
        while (iter.hasNext())
        {
            if (iter.next().getPathSpec().equals(pathSpec))
            {
                removed = true;
                iter.remove();
                break;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} to {}", removed ? "Removed" : "Ignored", pathSpec, this);
        return removed;
    }

    @Override
    public String toString()
    {
        return String.format("%s[size=%d]", this.getClass().getSimpleName(), _mappings.size());
    }
}
