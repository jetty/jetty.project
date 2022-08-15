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

package org.eclipse.jetty.server;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Interface to check aliases.
 */
public interface AliasCheck
{
    /**
     * Check if an alias is allowed to be served. If any {@link AliasCheck} returns
     * true then the alias will be allowed to be served, therefore any alias checker
     * should take things like the {@link ContextHandler#getProtectedTargets()} and
     * Security Constraints into consideration before allowing a return a value of true.
     *
     * @param pathInContext The path the aliased resource was created for.
     * @param resource The aliased resourced.
     * @return True if the resource is OK to be served.
     */
    boolean checkAlias(String pathInContext, Resource resource);
}
