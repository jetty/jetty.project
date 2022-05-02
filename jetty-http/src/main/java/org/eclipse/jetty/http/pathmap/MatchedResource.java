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

public class MatchedResource<E>
{
    private final MappedResource<E> mappedResource;
    private final MatchedPath matchedPath;

    public MatchedResource(MappedResource<E> resource, MatchedPath matchedPath)
    {
        this.mappedResource = resource;
        this.matchedPath = matchedPath;
    }

    public MappedResource<E> getMappedResource()
    {
        return this.mappedResource;
    }

    public PathSpec getPathSpec()
    {
        return mappedResource.getPathSpec();
    }

    public E getResource()
    {
        return mappedResource.getResource();
    }

    public MatchedPath getMatchedPath()
    {
        return matchedPath;
    }
}
