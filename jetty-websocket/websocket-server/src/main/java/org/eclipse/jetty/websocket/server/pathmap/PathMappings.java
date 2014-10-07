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

package org.eclipse.jetty.websocket.server.pathmap;

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
import org.eclipse.jetty.websocket.server.pathmap.PathMappings.MappedResource;

/**
 * Path Mappings of PathSpec to Resource.
 * <p>
 * Sorted into search order upon entry into the Set
 * 
 * @param <E>
 */
@ManagedObject("Path Mappings")
public class PathMappings<E> implements Iterable<MappedResource<E>>, Dumpable
{
    @ManagedObject("Mapped Resource")
    public static class MappedResource<E> implements Comparable<MappedResource<E>>
    {
        private final PathSpec pathSpec;
        private final E resource;

        public MappedResource(PathSpec pathSpec, E resource)
        {
            this.pathSpec = pathSpec;
            this.resource = resource;
        }

        /**
         * Comparison is based solely on the pathSpec
         */
        @Override
        public int compareTo(MappedResource<E> other)
        {
            return this.pathSpec.compareTo(other.pathSpec);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
            {
                return true;
            }
            if (obj == null)
            {
                return false;
            }
            if (getClass() != obj.getClass())
            {
                return false;
            }
            MappedResource<?> other = (MappedResource<?>)obj;
            if (pathSpec == null)
            {
                if (other.pathSpec != null)
                {
                    return false;
                }
            }
            else if (!pathSpec.equals(other.pathSpec))
            {
                return false;
            }
            return true;
        }

        @ManagedAttribute(value = "path spec", readonly = true)
        public PathSpec getPathSpec()
        {
            return pathSpec;
        }

        @ManagedAttribute(value = "resource", readonly = true)
        public E getResource()
        {
            return resource;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = (prime * result) + ((pathSpec == null)?0:pathSpec.hashCode());
            return result;
        }

        @Override
        public String toString()
        {
            return String.format("MappedResource[pathSpec=%s,resource=%s]",pathSpec,resource);
        }
    }

    private static final Logger LOG = Log.getLogger(PathMappings.class);
    private List<MappedResource<E>> mappings = new ArrayList<MappedResource<E>>();
    private MappedResource<E> defaultResource = null;

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

    public MappedResource<E> getMatch(String path)
    {
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

    public void put(PathSpec pathSpec, E resource)
    {
        MappedResource<E> entry = new MappedResource<>(pathSpec,resource);
        if (pathSpec.group == PathSpecGroup.DEFAULT)
        {
            defaultResource = entry;
        }
        // TODO: warning on replacement of existing mapping?
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
