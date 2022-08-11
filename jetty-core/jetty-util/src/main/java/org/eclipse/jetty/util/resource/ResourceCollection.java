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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

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
    private final List<Resource> _resources;

    /**
     * Instantiates a new resource collection.
     *
     * @param resources the resources to be added to collection
     */
    ResourceCollection(List<Resource> resources)
    {
        List<Resource> res = new ArrayList<>();
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
     * @throws MalformedURLException if the resolution of the path fails because the input path parameter is malformed against any of the collection
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
        Resource addedResource = null;
        for (Resource res : _resources)
        {
            addedResource = res.resolve(subUriPath);
            if (!addedResource.exists())
                continue;
            if (!addedResource.isDirectory())
                return addedResource; // Return simple (non-directory) Resource
            if (resources == null)
                resources = new ArrayList<>();
            resources.add(addedResource);
        }

        if (resources == null)
            return addedResource; // This will not exist

        if (resources.size() == 1)
            return resources.get(0);

        return new ResourceCollection(resources);
    }

    @Override
    public boolean exists()
    {
        for (Resource r : _resources)
        {
            if (r.exists())
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public Path getPath()
    {
        for (Resource r : _resources)
        {
            Path p = r.getPath();
            if (p != null)
                return p;
        }
        return null;
    }

    @Override
    public InputStream newInputStream() throws IOException
    {
        for (Resource r : _resources)
        {
            if (!r.exists())
            {
                // Skip, cannot open anyway
                continue;
            }
            InputStream is = r.newInputStream();
            if (is != null)
            {
                return is;
            }
        }

        throw new FileNotFoundException("Resource does not exist");
    }

    @Override
    public ReadableByteChannel newReadableByteChannel() throws IOException
    {
        for (Resource r : _resources)
        {
            ReadableByteChannel channel = r.newReadableByteChannel();
            if (channel != null)
            {
                return channel;
            }
        }
        return null;
    }

    @Override
    public String getName()
    {
        for (Resource r : _resources)
        {
            String name = r.getName();
            if (name != null)
            {
                return name;
            }
        }
        return null;
    }

    @Override
    public URI getURI()
    {
        for (Resource r : _resources)
        {
            URI uri = r.getURI();
            if (uri != null)
            {
                return uri;
            }
        }
        return null;
    }

    @Override
    public boolean isDirectory()
    {
        return true;
    }

    @Override
    public long lastModified()
    {
        for (Resource r : _resources)
        {
            long lm = r.lastModified();
            if (lm != -1)
            {
                return lm;
            }
        }
        return -1;
    }

    @Override
    public long length()
    {
        return -1;
    }

    /**
     * @return The list of resource names(merged) contained in the collection of resources.
     */
    @Override
    public List<String> list()
    {
        HashSet<String> set = new HashSet<>();
        for (Resource r : _resources)
        {
            List<String> list = r.list();
            if (list != null)
                set.addAll(list);
        }

        ArrayList<String> result = new ArrayList<>(set);
        result.sort(Comparator.naturalOrder());
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
