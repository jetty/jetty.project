//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.pathmap;

/**
 * Types of path spec groups.
 * <p>
 * This is used to facilitate proper pathspec search order.
 * <p>
 * Search Order: {@link PathSpecGroup#ordinal()} [increasin], {@link PathSpec#specLength} [decreasing], {@link PathSpec#pathSpec} [natural sort order]
 */
public enum PathSpecGroup
{
    // NOTE: Order of enums determines order of Groups.

    /**
     * For exactly defined path specs, no glob.
     */
    EXACT,
    /**
     * For path specs that have a hardcoded prefix and suffix with wildcard glob in the middle.
     * 
     * <pre>
     *   "^/downloads/[^/]*.zip$"  - regex spec
     *   "/a/{var}/c"              - websocket spec
     * </pre>
     * 
     * Note: there is no known servlet spec variant of this kind of path spec
     */
    MIDDLE_GLOB,
    /**
     * For path specs that have a hardcoded prefix and a trailing wildcard glob.
     * <p>
     * 
     * <pre>
     *   "/downloads/*"          - servlet spec
     *   "/api/*"                - servlet spec
     *   "^/rest/.*$"            - regex spec
     *   "/bookings/{guest-id}"  - websocket spec
     *   "/rewards/{vip-level}"  - websocket spec
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
     * Note: there is no known websocket spec variant of this kind of path spec
     */
    SUFFIX_GLOB,
    /**
     * The default spec for accessing the Root and/or Default behavior.
     * 
     * <pre>
     *   "/"           - servlet spec   (Default Servlet)
     *   "/"           - websocket spec (Root Context)
     *   "^/$"         - regex spec     (Root Context)
     * </pre>
     */
    DEFAULT;
}
