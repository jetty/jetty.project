//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

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
     * The path spec declaration as a string.
     *
     * @return the declaration as a string
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
     *  Test if a string is equivalent to this pathspec
     * @param pathSpec String representation to be normalized and then compared to the {@link #getDeclaration()} string.
     * @return true if the string, after normalization, represents the same pathspec
     */
    boolean is(String pathSpec);

    /**
     * Test to see if the provided path matches this path spec
     *
     * @param path the path to test
     * @return true if the path matches this path spec, false otherwise
     */
    boolean matches(String path);

    /**
     * A Mapping of one or more PathSpecs
     */
    class Mapping
    {
        private PathSpec[] _pathSpecs = {}; // never null

        /**
         * @param pathSpec The pathSpec to set, which is assumed to be a {@link ServletPathSpec}
         */
        public void addPathSpec(String pathSpec)
        {
            Objects.requireNonNull(pathSpec);
            addPathSpec(new ServletPathSpec(pathSpec));
        }

        /**
         * @param pathSpec The pathSpec to set, which may be an arbitrary type of pathspec
         */
        public void addPathSpec(PathSpec pathSpec)
        {
            Objects.requireNonNull(pathSpec);
            _pathSpecs = ArrayUtil.addToArray(_pathSpecs, pathSpec, PathSpec.class);
        }

        /**
         * Test if the list of path specs contains a particular one.
         *
         * @param pathSpec the path spec
         * @return true if path spec matches something in mappings
         */
        public boolean containsPathSpec(String pathSpec)
        {
            return stream().anyMatch(p -> p.is(pathSpec));
        }

        /**
         * Test if the list of path specs contains a particular one.
         *
         * @param pathSpec the path spec
         * @return true if path spec matches something in mappings
         */
        public boolean containsPathSpec(PathSpec pathSpec)
        {
            return stream().anyMatch(p -> p.equals(pathSpec));
        }

        /**
         * @return Returns only the {@link ServletPathSpec}s as strings or empty array.
         * @deprecated Use {@link #getServletPathSpecs()}
         */
        @Deprecated
        public String[] getPathSpecs()
        {
            return getServletPathSpecs();
        }

        /**
         * @return Returns only the {@link ServletPathSpec}s as strings or empty array.
         */
        @ManagedAttribute(value = "servlet url patterns", readonly = true)
        public String[] getServletPathSpecs()
        {
            return Arrays.stream(_pathSpecs)
                .filter(ServletPathSpec.class::isInstance)
                .map(PathSpec::getDeclaration)
                .toArray(String[]::new);
        }

        public boolean hasPathSpecs()
        {
            return _pathSpecs.length > 0;
        }

        /**
         * @param pathSpec The pathSpec to set, which is assumed to be a {@link ServletPathSpec}
         */
        public void setPathSpec(String pathSpec)
        {
            Objects.requireNonNull(pathSpec);
            _pathSpecs = new PathSpec[]{new ServletPathSpec(pathSpec)};
        }

        /**
         * @param pathSpec The pathSpec to set
         */
        public void setPathSpec(PathSpec pathSpec)
        {
            Objects.requireNonNull(pathSpec);
            _pathSpecs = new PathSpec[]{pathSpec};
        }

        /**
         * @param pathSpecs The pathSpecs to set, which are assumed to be {@link ServletPathSpec}s
         * @deprecated Use {@link #setServletPathSpecs(String[])}
         */
        @Deprecated
        public void setPathSpecs(String[] pathSpecs)
        {
            setServletPathSpecs(pathSpecs);
        }

        /**
         * @param pathSpecs The pathSpecs to set, which are assumed to be {@link ServletPathSpec}s
         */
        public void setPathSpecs(PathSpec[] pathSpecs)
        {
            _pathSpecs = (pathSpecs == null)
                ? new PathSpec[]{}
                : Arrays.stream(pathSpecs).filter(Objects::nonNull).toArray(PathSpec[]::new);
        }

        /**
         * @param pathSpecs The pathSpecs to set, which are assumed to be {@link ServletPathSpec}s
         */
        public void setPathSpecs(Collection<PathSpec> pathSpecs)
        {
            _pathSpecs = (pathSpecs == null)
                ? new PathSpec[]{}
                : pathSpecs.stream().filter(Objects::nonNull).toArray(PathSpec[]::new);
        }

        /**
         * @param pathSpecs The pathSpecs to set, which are assumed to be {@link ServletPathSpec}s
         */
        public void setServletPathSpecs(String[] pathSpecs)
        {
            _pathSpecs = (pathSpecs == null)
                ? new PathSpec[]{}
                : Arrays.stream(pathSpecs).filter(Objects::nonNull).map(ServletPathSpec::new).toArray(PathSpec[]::new);
        }

        public Stream<PathSpec> stream()
        {
            return Arrays.stream(_pathSpecs);
        }

        /**
         * @return Returns the pathSpecs as array of {@link PathSpec} instances or empty array.
         */
        @ManagedAttribute(value = "all path spec patterns", readonly = true)
        public PathSpec[] toPathSpecs()
        {
            return Arrays.copyOf(_pathSpecs, _pathSpecs.length);
        }
    }
}
