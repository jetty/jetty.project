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

package org.eclipse.jetty.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * This will approve an alias to any resource which is not a protected target.
 * Except symlinks...
 */
public class AllowedResourceAliasChecker implements ContextHandler.AliasCheck
{
    private static final Logger LOG = Log.getLogger(AllowedResourceAliasChecker.class);
    private final ContextHandler _contextHandler;
    private final boolean _checkSymlinkTargets;

    public AllowedResourceAliasChecker(ContextHandler contextHandler)
    {
        this(contextHandler, false);
    }

    public AllowedResourceAliasChecker(ContextHandler contextHandler, boolean checkSymlinkTargets)
    {
        _contextHandler = contextHandler;
        _checkSymlinkTargets = checkSymlinkTargets;
    }

    @Override
    public boolean check(String uri, Resource resource)
    {
        try
        {
            if (!resource.exists())
                return false;

            Path resourcePath = resource.getFile().toPath();
            Path realPath = resourcePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (isProtectedPath(realPath, false))
                return false;

            if (_checkSymlinkTargets && hasSymbolicLink(resourcePath))
            {
                realPath = resourcePath.toRealPath();
                if (isProtectedPath(realPath, true))
                    return false;
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            return false;
        }

        return true;
    }

    private boolean isProtectedPath(Path path, boolean followLinks) throws IOException
    {
        String basePath = followLinks ? _contextHandler.getBaseResource().getFile().toPath().toRealPath().toString()
            : _contextHandler.getBaseResource().getFile().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
        String targetPath = path.toString();

        if (!targetPath.startsWith(basePath))
            return true;

        for (String s : _contextHandler.getProtectedTargets())
        {
            String protectedTarget = new File(basePath, s).getCanonicalPath();
            if (StringUtil.startsWithIgnoreCase(targetPath, protectedTarget))
            {
                if (targetPath.length() == protectedTarget.length())
                    return true;

                // Check that the target prefix really is a path segment.
                char c = targetPath.charAt(protectedTarget.length());
                if (c == File.separatorChar)
                    return true;
            }
        }

        return false;
    }

    private boolean hasSymbolicLink(Path path)
    {
        // Is file itself a symlink?
        if (Files.isSymbolicLink(path))
            return true;

        // Lets try each path segment
        Path base = path.getRoot();
        for (Path segment : path)
        {
            base = base.resolve(segment);
            if (Files.isSymbolicLink(base))
                return true;
        }

        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{checkSymlinkTargets=%s}", AllowedResourceAliasChecker.class.getSimpleName(), hashCode(), _checkSymlinkTargets);
    }
}