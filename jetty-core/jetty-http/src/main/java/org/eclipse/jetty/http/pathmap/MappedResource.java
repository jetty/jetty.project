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

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("Mapped Resource")
public class MappedResource<E> implements Comparable<MappedResource<E>>
{
    private final PathSpec pathSpec;
    private final E resource;
    private final MatchedResource<E> preMatched;

    public MappedResource(PathSpec pathSpec, E resource)
    {
        this.pathSpec = pathSpec;
        this.resource = resource;

        MatchedResource<E> matched;
        switch (pathSpec.getGroup())
        {
            case ROOT:
                matched = new MatchedResource<>(resource, pathSpec, pathSpec.matched("/"));
                break;
            case EXACT:
                matched = new MatchedResource<>(resource, pathSpec, pathSpec.matched(pathSpec.getDeclaration()));
                break;
            default:
                matched = null;
        }
        this.preMatched = matched;
    }

    /**
     * @return A pre match {@link MatchedResource} for ROOT and EXACT matches, else null;
     */
    public MatchedResource<E> getPreMatched()
    {
        return preMatched;
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
        result = (prime * result) + ((pathSpec == null) ? 0 : pathSpec.hashCode());
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("MappedResource[pathSpec=%s,resource=%s]", pathSpec, resource);
    }
}
