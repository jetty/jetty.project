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

package org.eclipse.jetty.server.handler;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler.AliasCheck;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Symbolic Link AliasChecker.
 * <p>An instance of this class can be registered with {@link ContextHandler#addAliasCheck(AliasCheck)}
 * to check resources that are aliased to other locations.   The checker uses the
 * Java {@link Files#readSymbolicLink(Path)} and {@link Path#toRealPath(java.nio.file.LinkOption...)}
 * APIs to check if a file is aliased with symbolic links.</p>
 * @deprecated use {@link org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker} instead.
 */
@Deprecated
public class AllowSymLinkAliasChecker implements AliasCheck
{
    private static final Logger LOG = LoggerFactory.getLogger(AllowSymLinkAliasChecker.class);

    public AllowSymLinkAliasChecker()
    {
        LOG.warn("Deprecated, use SymlinkAllowedResourceAliasChecker instead.");
    }

    @Override
    public boolean check(String pathInContext, Resource resource)
    {
        // Only support PathResource alias checking
        if (!(resource instanceof PathResource pathResource))
            return false;

        try
        {
            Path path = pathResource.getPath();
            Path alias = pathResource.getAliasPath();

            if (PathResource.isSameName(alias, path))
                return false; // Unknown why this is an alias

            if (hasSymbolicLink(path) && Files.isSameFile(path, alias))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Allow symlink {} --> {}", resource, pathResource.getAliasPath());
                return true;
            }
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }

        return false;
    }

    private boolean hasSymbolicLink(Path path)
    {
        // Is file itself a symlink?
        if (Files.isSymbolicLink(path))
        {
            return true;
        }

        // Lets try each path segment
        Path base = path.getRoot();
        for (Path segment : path)
        {
            base = base.resolve(segment);
            if (Files.isSymbolicLink(base))
            {
                return true;
            }
        }

        return false;
    }
}
