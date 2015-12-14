//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
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
    private List<MappedResource<E>> mappings = new ArrayList<MappedResource<E>>();
    private MappedResource<E> defaultResource = null;
    private MappedResource<E> rootResource = null;

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dump(out,indent,mappings);
    }

    @ManagedAttribute(value = "mappings", readonly = true)
    public List<MappedResource<E>> getMappings()
    {
        return mappings;
    }

    public void reset()
    {
        mappings.clear();
    }
    
    /**
     * Return a list of MappedResource matches for the specified path.
     * 
     * @param path the path to return matches on
     * @return the list of mapped resource the path matches on
     */
    public List<MappedResource<E>> getMatches(String path)
    {
        boolean matchRoot = "/".equals(path);
        
        List<MappedResource<E>> ret = new ArrayList<>();
        int len = mappings.size();
        for (int i = 0; i < len; i++)
        {
            MappedResource<E> mr = mappings.get(i);

            switch (mr.getPathSpec().group)
            {
                case ROOT:
                    if (matchRoot)
                        ret.add(mr);
                    break;
                case DEFAULT:
                    if (matchRoot || mr.getPathSpec().matches(path))
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
        if (path.equals("/") && rootResource != null)
        {
            return rootResource;
        }
        
        int len = mappings.size();
        for (int i = 0; i < len; i++)
        {
            MappedResource<E> mr = mappings.get(i);
            if (mr.getPathSpec().matches(path))
            {
                return mr;
            }
        }
        return defaultResource;
    }

    @Override
    public Iterator<MappedResource<E>> iterator()
    {
        return mappings.iterator();
    }

    @SuppressWarnings("incomplete-switch")
    public void put(PathSpec pathSpec, E resource)
    {
        MappedResource<E> entry = new MappedResource<>(pathSpec,resource);
        switch (pathSpec.group)
        {
            case DEFAULT:
                defaultResource = entry;
                break;
            case ROOT:
                rootResource = entry;
                break;
        }
        
        // TODO: add warning when replacing an existing pathspec?
        
        mappings.add(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("Added {} to {}",entry,this);
        Collections.sort(mappings);
    }

    @Override
    public String toString()
    {
        return String.format("%s[size=%d]",this.getClass().getSimpleName(),mappings.size());
    }
}
