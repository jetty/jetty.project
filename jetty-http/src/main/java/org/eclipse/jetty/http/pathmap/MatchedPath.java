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

public interface MatchedPath
{
    MatchedPath EMPTY = new MatchedPath()
    {
        @Override
        public String getPathMatch()
        {
            return null;
        }

        @Override
        public String getPathInfo()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return MatchedPath.class.getSimpleName() + ".EMPTY";
        }
    };

    static MatchedPath from(String pathMatch, String pathInfo)
    {
        return new MatchedPath()
        {
            @Override
            public String getPathMatch()
            {
                return pathMatch;
            }

            @Override
            public String getPathInfo()
            {
                return pathInfo;
            }

            @Override
            public String toString()
            {
                return MatchedPath.class.getSimpleName() + "[pathMatch=" + pathMatch + ", pathInfo=" + pathInfo + "]";
            }
        };
    }

    /**
     * Return the portion of the path that matches a path spec.
     *
     * @return the path name portion of the match.
     */
    String getPathMatch();

    /**
     * Return the portion of the path that is after the path spec.
     *
     * @return the path info portion of the match, or null if there is no portion after the {@link #getPathMatch()}
     */
    String getPathInfo();
}
