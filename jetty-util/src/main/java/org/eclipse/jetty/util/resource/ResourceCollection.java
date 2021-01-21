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

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.URIUtil;

/**
 * A collection of resources (dirs).
 * Allows webapps to have multiple (static) sources.
 * The first resource in the collection is the main resource.
 * If a resource is not found in the main resource, it looks it up in
 * the order the resources were constructed.
 */
public class ResourceCollection extends Resource
{
    private Resource[] _resources;

    /**
     * Instantiates an empty resource collection.
     * <p>
     * This constructor is used when configuring jetty-maven-plugin.
     */
    public ResourceCollection()
    {
        _resources = new Resource[0];
    }

    /**
     * Instantiates a new resource collection.
     *
     * @param resources the resources to be added to collection
     */
    public ResourceCollection(Resource... resources)
    {
        List<Resource> list = new ArrayList<>();
        for (Resource r : resources)
        {
            if (r == null)
            {
                continue;
            }
            if (r instanceof ResourceCollection)
            {
                Collections.addAll(list, ((ResourceCollection)r).getResources());
            }
            else
            {
                list.add(r);
            }
        }
        _resources = list.toArray(new Resource[0]);
        for (Resource r : _resources)
        {
            assertResourceValid(r);
        }
    }

    /**
     * Instantiates a new resource collection.
     *
     * @param resources the resource strings to be added to collection
     */
    public ResourceCollection(String[] resources)
    {
        if (resources == null || resources.length == 0)
        {
            _resources = null;
            return;
        }

        ArrayList<Resource> res = new ArrayList<>();

        try
        {
            for (String strResource : resources)
            {
                if (strResource == null || strResource.length() == 0)
                {
                    throw new IllegalArgumentException("empty/null resource path not supported");
                }
                Resource resource = Resource.newResource(strResource);
                assertResourceValid(resource);
                res.add(resource);
            }

            if (res.isEmpty())
            {
                _resources = null;
                return;
            }

            _resources = res.toArray(new Resource[0]);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Instantiates a new resource collection.
     *
     * @param csvResources the string containing comma-separated resource strings
     */
    public ResourceCollection(String csvResources)
    {
        setResourcesAsCSV(csvResources);
    }

    /**
     * Retrieves the resource collection's resources.
     *
     * @return the resource array
     */
    public Resource[] getResources()
    {
        return _resources;
    }

    /**
     * Sets the resource collection's resources.
     *
     * @param resources the new resource array
     */
    public void setResources(Resource[] resources)
    {
        if (resources == null || resources.length == 0)
        {
            _resources = null;
            return;
        }

        List<Resource> res = new ArrayList<>();
        for (Resource resource : resources)
        {
            assertResourceValid(resource);
            res.add(resource);
        }

        if (res.isEmpty())
        {
            _resources = null;
            return;
        }

        _resources = res.toArray(new Resource[0]);
    }

    /**
     * Sets the resources as string of comma-separated values.
     * This method should be used when configuring jetty-maven-plugin.
     *
     * @param csvResources the comma-separated string containing
     * one or more resource strings.
     */
    public void setResourcesAsCSV(String csvResources)
    {
        if (csvResources == null)
        {
            throw new IllegalArgumentException("CSV String is null");
        }

        StringTokenizer tokenizer = new StringTokenizer(csvResources, ",;");
        int len = tokenizer.countTokens();
        if (len == 0)
        {
            throw new IllegalArgumentException("ResourceCollection@setResourcesAsCSV(String) " +
                " argument must be a string containing one or more comma-separated resource strings.");
        }

        List<Resource> res = new ArrayList<>();

        try
        {
            while (tokenizer.hasMoreTokens())
            {
                String token = tokenizer.nextToken().trim();
                // TODO: If we want to support CSV tokens with spaces then we should not trim here
                //       However, if we decide to to this, then CVS formatting/syntax becomes more strict.
                if (token.length() == 0)
                {
                    continue; // skip
                }
                Resource resource = Resource.newResource(token);
                assertResourceValid(resource);
                res.add(resource);
            }

            if (res.isEmpty())
            {
                _resources = null;
                return;
            }

            _resources = res.toArray(new Resource[0]);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param path The path segment to add
     * @return The contained resource (found first) in the collection of resources
     */
    @Override
    public Resource addPath(String path) throws IOException
    {
        assertResourcesSet();

        if (path == null)
        {
            throw new MalformedURLException();
        }

        if (path.length() == 0 || URIUtil.SLASH.equals(path))
        {
            return this;
        }

        Resource resource = null;
        ArrayList<Resource> resources = null;
        int i = 0;
        for (; i < _resources.length; i++)
        {
            resource = _resources[i].addPath(path);
            if (resource.exists())
            {
                if (resource.isDirectory())
                {
                    break;
                }
                return resource;
            }
        }

        for (i++; i < _resources.length; i++)
        {
            Resource r = _resources[i].addPath(path);
            if (r.exists() && r.isDirectory())
            {
                if (resources == null)
                {
                    resources = new ArrayList<>();
                }

                if (resource != null)
                {
                    resources.add(resource);
                    resource = null;
                }

                resources.add(r);
            }
        }

        if (resource != null)
        {
            return resource;
        }
        if (resources != null)
        {
            return new ResourceCollection(resources.toArray(new Resource[0]));
        }
        return null;
    }

    @Override
    public boolean delete() throws SecurityException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean exists()
    {
        assertResourcesSet();

        return true;
    }

    @Override
    public File getFile() throws IOException
    {
        assertResourcesSet();

        for (Resource r : _resources)
        {
            File f = r.getFile();
            if (f != null)
            {
                return f;
            }
        }
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        assertResourcesSet();

        for (Resource r : _resources)
        {
            InputStream is = r.getInputStream();
            if (is != null)
            {
                return is;
            }
        }
        return null;
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        assertResourcesSet();

        for (Resource r : _resources)
        {
            ReadableByteChannel channel = r.getReadableByteChannel();
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
        assertResourcesSet();

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
    public URL getURL()
    {
        assertResourcesSet();

        for (Resource r : _resources)
        {
            URL url = r.getURL();
            if (url != null)
            {
                return url;
            }
        }
        return null;
    }

    @Override
    public boolean isDirectory()
    {
        assertResourcesSet();

        return true;
    }

    @Override
    public long lastModified()
    {
        assertResourcesSet();

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
    public String[] list()
    {
        assertResourcesSet();
        HashSet<String> set = new HashSet<>();
        for (Resource r : _resources)
        {
            String[] list = r.list();
            if (list != null)
                Collections.addAll(set, list);
        }
        String[] result = set.toArray(new String[0]);
        Arrays.sort(result);
        return result;
    }

    @Override
    public void close()
    {
        assertResourcesSet();

        for (Resource r : _resources)
        {
            r.close();
        }
    }

    @Override
    public boolean renameTo(Resource dest) throws SecurityException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyTo(File destination)
        throws IOException
    {
        assertResourcesSet();

        for (int r = _resources.length; r-- > 0; )
        {
            _resources[r].copyTo(destination);
        }
    }

    /**
     * @return the list of resources separated by a path separator
     */
    @Override
    public String toString()
    {
        if (_resources == null || _resources.length == 0)
        {
            return "[]";
        }

        return String.valueOf(Arrays.asList(_resources));
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        // TODO could look at implementing the semantic of is this collection a subset of the Resource r?
        return false;
    }

    private void assertResourcesSet()
    {
        if (_resources == null || _resources.length == 0)
        {
            throw new IllegalStateException("*resources* not set.");
        }
    }

    private void assertResourceValid(Resource resource)
    {
        if (resource == null)
        {
            throw new IllegalStateException("Null resource not supported");
        }

        if (!resource.exists() || !resource.isDirectory())
        {
            throw new IllegalArgumentException(resource + " is not an existing directory.");
        }
    }
}
