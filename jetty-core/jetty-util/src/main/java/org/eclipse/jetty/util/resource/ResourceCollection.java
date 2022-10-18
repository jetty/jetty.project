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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.URIUtil;

/**
 * A collection of Resources.
 * Allows webapps to have multiple sources.
 * The first resource in the collection is the main resource.
 * If a resource is not found in the main resource, it looks it up in
 * the order the provided in the constructors
 */
public class ResourceCollection extends Resource
{
    /**
     * Stream a resource to its component resources (if any).
     * @param resource The resource to stream
     * @return If the passed resource is a ResourceCollection, then it's contained
     *         resources are streamed, otherwise the passed resource itself if streamed
     */
    public static Stream<Resource> stream(Resource resource)
    {
        if (resource instanceof ResourceCollection rc)
            return rc.getResources().stream();
        return Stream.of(resource);
    }

    private final List<Resource> _resources;

    /**
     * Instantiates a new resource collection.
     *
     * @param resources the resources to be added to collection
     */
    ResourceCollection(List<Resource> resources)
    {
        List<Resource> res = new ArrayList<>(resources.size());
        gatherUniqueFlatResourceList(res, resources);
        _resources = Collections.unmodifiableList(res);
    }

    private static void gatherUniqueFlatResourceList(List<Resource> unique, List<Resource> resources)
    {
        if (resources == null || resources.isEmpty())
            throw new IllegalArgumentException("Empty Resource collection");

        for (Resource r : resources)
        {
            if (r == null)
            {
                throw new IllegalArgumentException("Null Resource entry encountered");
            }

            if (r instanceof ResourceCollection resourceCollection)
            {
                gatherUniqueFlatResourceList(unique, resourceCollection.getResources());
            }
            else
            {
                if (unique.contains(r))
                {
                    // skip, already seen
                    continue;
                }

                if (!r.exists())
                {
                    throw new IllegalArgumentException("Does not exist: " + r);
                }

                if (!r.isDirectory())
                {
                    throw new IllegalArgumentException("Non-Directory not allowed: " + r);
                }

                unique.add(r);
            }
        }
    }

    /**
     * Retrieves the resource collection's resources.
     *
     * @return the resource collection
     */
    public List<Resource> getResources()
    {
        return _resources;
    }

    /**
     * Resolves a path against the resource collection.
     *
     * @param subUriPath The path segment to add
     * @return The resulting resource(s) :
     * <ul>
     *   <li>is a file that exists in at least one of the collection, then the first one found is returned</li>
     *   <li>is a directory that exists in at exactly one of the collection, then that directory resource is returned </li>
     *   <li>is a directory that exists in several of the collection, then a ResourceCollection of those directories is returned</li>
     *   <li>do not exist in any of the collection, then a new non existent resource relative to the first in the collection is returned.</li>
     * </ul>
     */
    @Override
    public Resource resolve(String subUriPath)
    {
        if (URIUtil.isNotNormalWithinSelf(subUriPath))
            throw new IllegalArgumentException(subUriPath);

        if (subUriPath.length() == 0 || URIUtil.SLASH.equals(subUriPath))
        {
            return this;
        }

        ArrayList<Resource> resources = null;

        // Attempt a simple (single) Resource lookup that exists
        Resource resolved = null;
        for (Resource res : _resources)
        {
            resolved = res.resolve(subUriPath);
            if (!resolved.exists())
                continue;
            if (!resolved.isDirectory())
                return resolved; // Return simple (non-directory) Resource
            if (resources == null)
                resources = new ArrayList<>();
            resources.add(resolved);
        }

        if (resources == null)
            return resolved; // This will not exist

        if (resources.size() == 1)
            return resources.get(0);

        return new ResourceCollection(resources);
    }

    @Override
    public boolean exists()
    {
        return _resources.stream().anyMatch(Resource::exists);
    }

    @Override
    public Path getPath()
    {
        return null;
    }

    @Override
    public String getName()
    {
        return null;
    }

    @Override
    public String getFileName()
    {
        String filename = null;
        // return a non-null filename only if all resources agree on the same name.
        for (Resource r : _resources)
        {
            String fn = r.getFileName();
            if (fn == null)
                return null;
            if (filename == null)
                filename = fn;
            else if (!filename.equals(fn))
                return null;
        }
        return filename;
    }

    @Override
    public URI getURI()
    {
        return null;
    }

    @Override
    public boolean isDirectory()
    {
        return true;
    }

    @Override
    public Instant lastModified()
    {
        Instant instant = null;
        for (Resource r : _resources)
        {
            Instant lm = r.lastModified();
            if (instant == null || lm.isAfter(instant))
            {
                instant = lm;
            }
        }
        return instant;
    }

    @Override
    public long length()
    {
        return -1;
    }

    @Override
    public Iterator<Resource> iterator()
    {
        return _resources.iterator();
    }

    @Override
    public List<Resource> list()
    {
        List<Resource> result = new ArrayList<>();
        for (Resource r : _resources)
        {
            if (r.isDirectory())
                result.addAll(r.list());
            else
                result.add(r);
        }
        return result;
    }

    @Override
    public void copyTo(Path destination) throws IOException
    {
        // Copy in reverse order
        for (int r = _resources.size(); r-- > 0; )
        {
            _resources.get(r).copyTo(destination);
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ResourceCollection other = (ResourceCollection)o;
        return Objects.equals(_resources, other._resources);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(_resources);
    }

    /**
     * @return the list of resources
     */
    @Override
    public String toString()
    {
        return _resources.stream()
            .map(Resource::getName)
            .collect(Collectors.joining(", ", "[", "]"));
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        // TODO could look at implementing the semantic of is this collection a subset of the Resource r?
        return false;
    }
}
