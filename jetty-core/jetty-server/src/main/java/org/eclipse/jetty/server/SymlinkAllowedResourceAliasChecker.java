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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An extension of {@link AllowedResourceAliasChecker} which will allow symlinks alias to arbitrary
 * targets, so long as the symlink file itself is an allowed resource. Non symlinked paths are never
 * allowed by this {@link AliasCheck}.
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

    public SymlinkAllowedResourceAliasChecker(ContextHandler contextHandler, Resource baseResource)
    {
        super(contextHandler, baseResource);
    }

    public SymlinkAllowedResourceAliasChecker(ContextHandler contextHandler, Supplier<Resource> resourceBaseSupplier)
    {
        super(contextHandler, resourceBaseSupplier);
    }

    @Override
    protected boolean check(String pathInContext, Path path)
    {
        if (_baseResource == null)
            return false;

        // do not allow any file separation characters in the URI, as we need to know exactly what are the segments
        if (File.separatorChar != '/' && pathInContext.indexOf(File.separatorChar) >= 0)
            return false;

        // Split the URI path into segments, to walk down the resource tree and build the realURI of any symlink found
        // We rebuild the realURI, segment by segment, getting the real name at each step, so that we can distinguish between
        // alias types.  Specifically, so we can allow a symbolic link so long as it's realpath is not protected.
        String[] segments = pathInContext.substring(1).split("/");
        StringBuilder segmentPath = new StringBuilder();

        try
        {
            for (String segment : segments)
            {
                // Add the segment to the path and realURI.
                segmentPath.append("/").append(segment);
                Resource fromBase = _baseResource.resolve(segmentPath.toString());

                // If there is a single path, check it
                Path p = fromBase.getPath();
                if (p != null)
                {
                    // If the ancestor of the alias is a symlink, then check if the real URI is protected, otherwise allow.
                    // This allows symlinks like /other->/WEB-INF and /external->/var/lib/docroot
                    // This does not allow symlinks like /WeB-InF->/var/lib/other
                    if (Files.isSymbolicLink(p))
                        return !getContextHandler().isProtectedTarget(segmentPath.toString());

                    // If the ancestor is not allowed then do not allow.
                    if (!isAllowed(p))
                        return false;
                }
                else
                {
                    // otherwise check all possibles
                    for (Resource r : fromBase)
                    {
                        p = r.getPath();
                        if (!Files.exists(p))
                            continue;

                        // If the ancestor of the alias is a symlink, then check if the real URI is protected, otherwise allow.
                        // This allows symlinks like /other->/WEB-INF and /external->/var/lib/docroot
                        // This does not allow symlinks like /WeB-InF->/var/lib/other
                        if (Files.isSymbolicLink(p))
                            return !getContextHandler().isProtectedTarget(segmentPath.toString());

                        // If the ancestor is not allowed then do not allow.
                        if (!isAllowed(p))
                            return false;
                    }
                }

                // TODO as we are building the realURI of the resource, it would be possible to
                //  re-check that against security constraints.
            }
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failed to check alias", t);
            return false;
        }

        // No symlink found, so must not be allowed.
        return false;
    }
}
