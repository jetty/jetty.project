//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Objects;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>This will approve an alias where the only difference is a trailing slash.</p>
 * <p>For example a path to <code>/context/dir/index.html/</code> can be approved as an alias to
 * <code>/context/dir/index.html</code>.</p>
 */
public class TrailingSlashAliasChecker extends AbstractLifeCycle implements AliasCheck
{
    @Override
    public boolean checkAlias(String pathInContext, Resource resource)
    {
        String uri = resource.getURI().toString();
        if (uri.isEmpty())
            return false;

        String realUri = resource.getRealURI().toString();
        if (uri.endsWith("/") && !realUri.endsWith("/"))
            return Objects.equals(uri.substring(0, uri.length() - 1), realUri);

        return false;
    }
}
