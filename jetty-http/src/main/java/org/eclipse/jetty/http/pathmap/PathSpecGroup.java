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
 * Types of path spec groups.
 * <p>
 * This is used to facilitate proper pathspec search order.
 * <p>
 * Search Order:
 * <ol>
 * <li>{@link PathSpecGroup#ordinal()} [increasing]</li>
 * <li>{@link PathSpec#getSpecLength()} [decreasing]</li>
 * <li>{@link PathSpec#getDeclaration()} [natural sort order]</li>
 * </ol>
 */
public enum PathSpecGroup
{
    // NOTE: Order of enums determines order of Groups.

    /**
     * The root spec for accessing the Root behavior.
     *
     * <pre>
     *   ""           - servlet spec       (Root Servlet)
     *   null         - legacy             (Root Servlet)
     * </pre>
     *
     * Note: there is no known uri-template spec variant of this kind of path spec
     */
    ROOT,
    /**
     * For exactly defined path specs, no glob.
     */
    EXACT,
    /**
     * For path specs that have a hardcoded prefix and suffix with wildcard glob in the middle.
     *
     * <pre>
     *   "^/downloads/[^/]*.zip$"  - regex spec
     *   "/a/{var}/c"              - uri-template spec
     * </pre>
     *
     * Note: there is no known servlet spec variant of this kind of path spec
     */
    MIDDLE_GLOB,
    /**
     * For path specs that have a hardcoded prefix and a trailing wildcard glob.
     *
     * <pre>
     *   "/downloads/*"          - servlet spec
     *   "/api/*"                - servlet spec
     *   "^/rest/.*$"            - regex spec
     *   "/bookings/{guest-id}"  - uri-template spec
     *   "/rewards/{vip-level}"  - uri-template spec
     * </pre>
     */
    PREFIX_GLOB,
    /**
     * For path specs that have a wildcard glob with a hardcoded suffix
     *
     * <pre>
     *   "*.do"        - servlet spec
     *   "*.css"       - servlet spec
     *   "^.*\.zip$"   - regex spec
     * </pre>
     *
     * Note: there is no known uri-template spec variant of this kind of path spec
     */
    SUFFIX_GLOB,
    /**
     * The default spec for accessing the Default path behavior.
     *
     * <pre>
     *   "/"           - servlet spec      (Default Servlet)
     *   "/"           - uri-template spec (Root Context)
     *   "^/$"         - regex spec        (Root Context)
     * </pre>
     *
     * Per Servlet Spec, pathInfo is always null for these specs.
     * If nothing above matches, then default will match.
     */
    DEFAULT,
}
