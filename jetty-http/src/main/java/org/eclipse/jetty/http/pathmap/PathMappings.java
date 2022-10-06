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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

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
public class PathMappings<E> implements Iterable<MappedResource<E>>, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(PathMappings.class);
    private final Set<MappedResource<E>> _mappings = new TreeSet<>(Comparator.comparing(MappedResource::getPathSpec));

    private boolean _optimizedExact = true;
    private final Index.Mutable<MappedResource<E>> _exactMap = new Index.Builder<MappedResource<E>>()
        .caseSensitive(true)
        .mutable()
        .build();
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
                // New group, reset skip logic
                skipRestOfGroup = false;

                // New group in list, so let's look for an optimization
                switch (group)
                {
                    case EXACT:
                    {
                        if (_optimizedExact)
                        {
                            int i = path.length();
                            while (i >= 0)
                            {
                                MappedResource<E> candidate = _exactMap.getBest(path, 0, i--);
                                if (candidate == null)
                                    continue;

                                matchedPath = candidate.getPathSpec().matched(path);
                                if (matchedPath != null)
                                {
                                    return new MatchedResource<>(candidate.getResource(), candidate.getPathSpec(), matchedPath);
                                }
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
                            while (i >= 0)
                            {
                                MappedResource<E> candidate = _prefixMap.getBest(path, 0, i--);
                                if (candidate == null)
                                    continue;

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

    /**
     * @deprecated use {@link #getMatched(String)} instead
     */
    @Deprecated(forRemoval = true)
    public MappedResource<E> getMatch(String path)
    {
        MatchedResource<E> matchedPath = getMatched(path);
        return new MappedResource<>(matchedPath.getPathSpec(), matchedPath.getResource());
    }

    @Override
    public Iterator<MappedResource<E>> iterator()
    {
        return _mappings.iterator();
    }

    /**
     * @deprecated use {@link PathSpec#from(String)} instead
     */
    @Deprecated(forRemoval = true)
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
        boolean added = _mappings.add(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} to {}", added ? "Added" : "Ignored", entry, this);

        if (added)
        {
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
                    }
                    break;
                default:
            }
        }

        return added;
    }

    @SuppressWarnings("incomplete-switch")
    public boolean remove(PathSpec pathSpec)
    {
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

        if (removed)
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
                    }
                    break;
                case PREFIX_GLOB:
                    String prefix = pathSpec.getPrefix();
                    if (prefix != null)
                    {
                        _prefixMap.remove(prefix);
                        // Recalculate _optimizePrefix
                        _optimizedPrefix = canBeOptimized(PathSpecGroup.PREFIX_GLOB);
                    }
                    break;
                case SUFFIX_GLOB:
                    String suffix = pathSpec.getSuffix();
                    if (suffix != null)
                    {
                        _suffixMap.remove(suffix);
                        // Recalculate _optimizeSuffix
                        _optimizedSuffix = canBeOptimized(PathSpecGroup.SUFFIX_GLOB);
                    }
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

    @Override
    public String toString()
    {
        return String.format("%s[size=%d]", this.getClass().getSimpleName(), _mappings.size());
    }
}
