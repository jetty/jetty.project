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
import java.util.Optional;
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

    private final Index.Mutable<MappedResource<E>> _exactMap = new Index.Builder<MappedResource<E>>()
        .caseSensitive(true)
        .mutable()
        .build();
    private final Index.Mutable<MappedResource<E>> _prefixMap = new Index.Builder<MappedResource<E>>()
        .caseSensitive(true)
        .mutable()
        .build();
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
                    if (isRootPath || mr.getPathSpec().matches(path))
                        ret.add(mr);
                    break;
                default:
                    if (mr.getPathSpec().matches(path))
                        ret.add(mr);
                    break;
            }
        }
        return ret;
    }

    public MappedResource<E> getMatch(String path)
    {
        PathSpecGroup lastGroup = null;

        // Search all the mappings
        for (MappedResource<E> mr : _mappings)
        {
            PathSpecGroup group = mr.getPathSpec().getGroup();
            if (group != lastGroup)
            {
                // New group in list, so let's look for an optimization
                switch (group)
                {
                    case EXACT:
                    {
                        int i = path.length();
                        while (i >= 0)
                        {
                            MappedResource<E> candidate = _exactMap.getBest(path, 0, i);
                            if (candidate == null)
                                break;
                            if (candidate.getPathSpec().matches(path))
                                return candidate;
                            i = candidate.getPathSpec().getPrefix().length() - 1;
                        }
                        break;
                    }

                    case PREFIX_GLOB:
                    {
                        int i = path.length();
                        while (i >= 0)
                        {
                            MappedResource<E> candidate = _prefixMap.getBest(path, 0, i);
                            if (candidate == null)
                                break;
                            if (candidate.getPathSpec().matches(path))
                                return candidate;
                            i = candidate.getPathSpec().getPrefix().length() - 1;
                        }
                        break;
                    }

                    case SUFFIX_GLOB:
                    {
                        int i = 0;
                        while ((i = path.indexOf('.', i + 1)) > 0)
                        {
                            MappedResource<E> candidate = _suffixMap.get(path, i + 1, path.length() - i - 1);
                            if (candidate != null && candidate.getPathSpec().matches(path))
                                return candidate;
                        }
                        break;
                    }

                    default:
                }
            }

            if (mr.getPathSpec().matches(path))
                return mr;

            lastGroup = group;
        }

        return null;
    }

    @Override
    public Iterator<MappedResource<E>> iterator()
    {
        return _mappings.iterator();
    }

    public static PathSpec asPathSpec(String pathSpecString)
    {
        if ((pathSpecString == null) || (pathSpecString.length() < 1))
        {
            if (pathSpecString != null)
                return new ServletPathSpec("");
            throw new RuntimeException("Path Spec String must start with '^', '/', or '*.': got [" + pathSpecString + "]");
        }
        return pathSpecString.charAt(0) == '^' ? new RegexPathSpec(pathSpecString) : new ServletPathSpec(pathSpecString);
    }

    public E get(PathSpec spec)
    {
        Optional<E> optionalResource = _mappings.stream()
            .filter(mappedResource -> mappedResource.getPathSpec().equals(spec))
            .map(mappedResource -> mappedResource.getResource())
            .findFirst();
        if (!optionalResource.isPresent())
            return null;

        return optionalResource.get();
    }

    public boolean put(String pathSpecString, E resource)
    {
        return put(asPathSpec(pathSpecString), resource);
    }

    public boolean put(PathSpec pathSpec, E resource)
    {
        MappedResource<E> entry = new MappedResource<>(pathSpec, resource);
        switch (pathSpec.getGroup())
        {
            case EXACT:
                String exact = pathSpec.getPrefix();
                if (exact != null)
                    _exactMap.put(exact, entry);
                break;
            case PREFIX_GLOB:
                String prefix = pathSpec.getPrefix();
                if (prefix != null)
                    _prefixMap.put(prefix, entry);
                break;
            case SUFFIX_GLOB:
                String suffix = pathSpec.getSuffix();
                if (suffix != null)
                    _suffixMap.put(suffix, entry);
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
        String prefix = pathSpec.getPrefix();
        String suffix = pathSpec.getSuffix();
        switch (pathSpec.getGroup())
        {
            case EXACT:
                if (prefix != null)
                    _exactMap.remove(prefix);
                break;
            case PREFIX_GLOB:
                if (prefix != null)
                    _prefixMap.remove(prefix);
                break;
            case SUFFIX_GLOB:
                if (suffix != null)
                    _suffixMap.remove(suffix);
                break;
            default:
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
