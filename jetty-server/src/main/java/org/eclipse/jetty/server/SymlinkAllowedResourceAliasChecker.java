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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An extension of {@link AllowedResourceAliasChecker} which will allow symlinks alias to arbitrary
 * targets, so long as the symlink file itself is an allowed resource.
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
    protected boolean check(String pathInContext, Path path)
    {
        // Split the URI path so we can walk down the resource tree and build the realURI of any symlink found
        String[] segments = pathInContext.substring(1).split("/");
        Path fromBase = _base;
        StringBuilder realURI = new StringBuilder();

        try
        {
            for (String segment : segments)
            {
                // Add the segment to the path and realURI.
                fromBase = fromBase.resolve(segment);
                realURI.append("/").append(fromBase.toRealPath(NO_FOLLOW_LINKS).getFileName());

                // If the ancestor of the alias is a symlink, then check if the real URI is protected, otherwise allow.
                // This will allow a symlink like /other->/WEB-INF, but not /WeB-InF->/var/lib/other
                if (Files.isSymbolicLink(fromBase))
                    return !getContextHandler().isProtectedTarget(realURI.toString());

                // If the ancestor is not allowed then do not allow.
                if (!isAllowed(fromBase))
                    return false;
            }
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failed to check alias", t);
            return false;
        }

        // No symlink found, so must be allowed. Double check it is the right path we checked.
        return isSameFile(fromBase, path);
    }
}
