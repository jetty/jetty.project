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

/**
 * A path specification is a URI path template that can be matched against.
 * <p>
 * Implementors <i>must</i> override {@link Object#equals(Object)} and {@link Object#hashCode()}.
 */
public interface PathSpec extends Comparable<PathSpec>
{
    /**
     * The length of the spec.
     *
     * @return the length of the spec.
     */
    int getSpecLength();

    /**
     * The spec group.
     *
     * @return the spec group.
     */
    PathSpecGroup getGroup();

    /**
     * Get the number of path elements that this path spec declares.
     * <p>
     * This is used to determine longest match logic.
     *
     * @return the depth of the path segments that this spec declares
     */
    int getPathDepth();

    /**
     * Return the portion of the path that is after the path spec.
     *
     * @param path the path to match against
     * @return the path info portion of the string
     * @deprecated use {@link #matched(String)} instead
     */
    @Deprecated
    String getPathInfo(String path);

    /**
     * Return the portion of the path that matches a path spec.
     *
     * @param path the path to match against
     * @return the match, or null if no match at all
     * @deprecated use {@link #matched(String)} instead
     */
    @Deprecated
    String getPathMatch(String path);

    /**
     * The as-provided path spec.
     *
     * @return the as-provided path spec
     */
    String getDeclaration();

    /**
     * A simple prefix match for the pathspec or null
     *
     * @return A simple prefix match for the pathspec or null
     */
    String getPrefix();

    /**
     * A simple suffix match for the pathspec or null
     *
     * @return A simple suffix match for the pathspec or null
     */
    String getSuffix();

    /**
     * Test to see if the provided path matches this path spec
     *
     * @param path the path to test
     * @return true if the path matches this path spec, false otherwise
     * @deprecated use {@link #matched(String)} instead
     */
    @Deprecated
    boolean matches(String path);

    /**
     * Get the complete matched details of the provided path.
     * @param path the path to test
     * @return the matched details, if a match was possible, or null if not able to be matched.
     */
    MatchedPath matched(String path);
}
