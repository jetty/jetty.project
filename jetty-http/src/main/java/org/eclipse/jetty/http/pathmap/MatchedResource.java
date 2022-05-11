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

import java.util.Map;

public class MatchedResource<E>
{
    private final E resource;
    private final PathSpec pathSpec;
    private final MatchedPath matchedPath;

    public MatchedResource(E resource, PathSpec pathSpec, MatchedPath matchedPath)
    {
        this.resource = resource;
        this.pathSpec = pathSpec;
        this.matchedPath = matchedPath;
    }

    public static <E> MatchedResource<E> of(Map.Entry<PathSpec, E> mapping, MatchedPath matchedPath)
    {
        return new MatchedResource<>(mapping.getValue(), mapping.getKey(), matchedPath);
    }

    public PathSpec getPathSpec()
    {
        return this.pathSpec;
    }

    public E getResource()
    {
        return this.resource;
    }

    /**
     * Return the portion of the path that matches a path spec.
     *
     * @return the path name portion of the match.
     */
    public String getPathMatch()
    {
        return matchedPath.getPathMatch();
    }

    /**
     * Return the portion of the path that is after the path spec.
     *
     * @return the path info portion of the match, or null if there is no portion after the {@link #getPathMatch()}
     */
    public String getPathInfo()
    {
        return matchedPath.getPathInfo();
    }
}
