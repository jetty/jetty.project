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
import java.util.Objects;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>This will approve any alias to anything inside of the {@link ContextHandler}s resource base which
 * is not protected by {@link ContextHandler#isProtectedTarget(String)}.</p>
 * <p>This will approve symlinks to outside of the resource base. This can be optionally configured to check that the
 * target of the symlinks is also inside of the resource base and is not a protected target.</p>
 * <p>Aliases approved by this may still be able to bypass SecurityConstraints, so this class would need to be extended
 * to enforce any additional security constraints that are required.</p>
 */
public class AllowedResourceAliasChecker implements ContextHandler.AliasCheck
{
    private static final Logger LOG = Log.getLogger(AllowedResourceAliasChecker.class);
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[0];
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

    private final ContextHandler _contextHandler;
    private final boolean _checkSymlinkTargets;

    /**
     * @param contextHandler the context handler to use.
     * @param checkSymlinkTargets true to check that the target of the symlink is an allowed resource.
     */
    public AllowedResourceAliasChecker(ContextHandler contextHandler, boolean checkSymlinkTargets)
    {
        _contextHandler = contextHandler;
        _checkSymlinkTargets = checkSymlinkTargets;
    }

    @Override
    public boolean check(String uri, Resource resource)
    {
        // The existence check resolves the symlinks.
        if (!resource.exists())
            return false;

        Path resourcePath = getPath(resource);
        if (resourcePath == null)
            return false;

        try
        {
            if (isProtectedPath(resourcePath, NO_FOLLOW_LINKS))
                return false;

            if (_checkSymlinkTargets && hasSymbolicLink(resourcePath))
            {
                if (isProtectedPath(resourcePath, FOLLOW_LINKS))
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

    protected boolean isProtectedPath(Path resourcePath, LinkOption[] linkOptions) throws IOException
    {
        String basePath = Objects.requireNonNull(getPath(_contextHandler.getBaseResource())).toRealPath(linkOptions).toString();
        String targetPath = resourcePath.toRealPath(linkOptions).toString();

        if (!targetPath.startsWith(basePath))
            return true;

        String[] protectedTargets = _contextHandler.getProtectedTargets();
        if (protectedTargets != null)
        {
            for (String s : protectedTargets)
            {
                // TODO: we are always following links for the base resource.
                //  We cannot use toRealPath(linkOptions) here as it throws if file does not exist,
                //  and the protected targets do not have to always exist.
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
        }

        return false;
    }

    protected boolean hasSymbolicLink(Path path)
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

    private Path getPath(Resource resource)
    {
        try
        {
            if (resource instanceof PathResource)
                return ((PathResource)resource).getPath();
            return resource.getFile().toPath();
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{checkSymlinkTargets=%s}", AllowedResourceAliasChecker.class.getSimpleName(), hashCode(), _checkSymlinkTargets);
    }
}