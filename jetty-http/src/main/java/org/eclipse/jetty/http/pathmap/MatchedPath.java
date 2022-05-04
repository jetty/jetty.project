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
            return "MatchedPath.EMPTY";
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
                return "MatchedPath.from[pathMatch=" + pathMatch + ", pathInfo=" + pathInfo + "]";
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
