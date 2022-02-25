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
     */
    String getPathInfo(String path);

    /**
     * Return the portion of the path that matches a path spec.
     *
     * @param path the path to match against
     * @return the match, or null if no match at all
     */
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
     */
    boolean matches(String path);
}
