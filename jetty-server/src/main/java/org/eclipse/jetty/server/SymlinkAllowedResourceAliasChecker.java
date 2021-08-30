//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An extension of {@link AllowedResourceAliasChecker} which only allows aliased resources if they are symlinks.
 */
public class SymlinkAllowedResourceAliasChecker extends AllowedResourceAliasChecker
{
    private static final Logger LOG = LoggerFactory.getLogger(SymlinkAllowedResourceAliasChecker.class);

    /**
     * @param contextHandler the context handler to use.
     */
    public SymlinkAllowedResourceAliasChecker(ContextHandler contextHandler)
    {
        super(contextHandler);
    }

    @Override
    public boolean check(String pathInContext, Resource resource)
    {
        // The existence check resolves the symlinks.
        if (!resource.exists())
            return false;

        Path path = getPath(resource);
        if (path == null)
            return false;

        String[] segments = pathInContext.split("/");
        Path fromBase = _base;

        try
        {
            for (String segment : segments)
            {
                fromBase = fromBase.resolve(segment);
                if (!Files.exists(fromBase))
                    return false;
                if (Files.isSymbolicLink(fromBase))
                    return true;
                if (!isAllowed(fromBase))
                    return false;
            }
        }
        catch (Throwable t)
        {
            LOG.warn("Failed to check alias", t);
            return false;
        }
        
        return true;
    }
}
