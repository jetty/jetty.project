//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common;

import java.net.URI;
import java.security.Principal;

public interface UpgradeRequest
{
    /**
     * For {@link javax.websocket.Session#getUserPrincipal()}
     *
     * @return the User {@link Principal} present during the Upgrade Request
     */
    Principal getUserPrincipal();

    /**
     * @return the full URI of this request.
     */
    URI getRequestURI();

    /**
     * For obtaining {@link javax.websocket.server.PathParam} values from the Request context path.
     *
     * @return the path in Context.
     */
    String getPathInContext();
}
