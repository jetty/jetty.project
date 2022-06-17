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

import java.util.Map;

/**
 * The match details when using {@link PathMappings#getMatched(String)}, used to minimize return to the PathSpec or PathMappings for subsequent details
 * that are now provided by the {@link MatchedPath} instance.
 *
 * @param <E> the type of resource (IncludeExclude uses boolean, WebSocket uses endpoint/endpoint config, servlet uses ServletHolder, etc)
 */
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

    public MatchedPath getMatchedPath()
    {
        return this.matchedPath;
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
