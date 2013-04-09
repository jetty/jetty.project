//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jetty.websocket.jsr356.server.pathmap.PathMappings.MappedResource;

/**
 * Path Mappings of PathSpec to Resource.
 * <p>
 * Sorted into search order upon entry into the Set
 * 
 * @param <E>
 */
public class PathMappings<E> implements Iterable<MappedResource<E>>
{
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

        public PathSpec getPathSpec()
        {
            return pathSpec;
        }

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

    private Set<MappedResource<E>> mappings = new TreeSet<MappedResource<E>>();
    private MappedResource<E> defaultResource = null;

    public MappedResource<E> getMatch(String path)
    {
        for (MappedResource<E> mr : mappings)
        {
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
    }
}
