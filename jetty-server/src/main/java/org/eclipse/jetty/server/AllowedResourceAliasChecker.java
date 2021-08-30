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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This will approve any alias to anything inside of the {@link ContextHandler}s resource base which
 * is not protected by a protected target as defined by {@link ContextHandler#getProtectedTargets()} at start.</p>
 * <p>Aliases approved by this may still be able to bypass SecurityConstraints, so this class would need to be extended
 * to enforce any additional security constraints that are required.</p>
 */
public class AllowedResourceAliasChecker extends AbstractLifeCycle implements ContextHandler.AliasCheck
{
    private static final Logger LOG = LoggerFactory.getLogger(AllowedResourceAliasChecker.class);
    protected static final LinkOption[] FOLLOW_LINKS = new LinkOption[0];
    protected static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

    private final ContextHandler _contextHandler;
    private final List<Path> _protected = new ArrayList<>();
    protected Path _base;

    /**
     * @param contextHandler the context handler to use.
     */
    public AllowedResourceAliasChecker(ContextHandler contextHandler)
    {
        _contextHandler = contextHandler;
    }

    protected ContextHandler getContextHandler()
    {
        return _contextHandler;
    }

    @Override
    protected void doStart() throws Exception
    {
        _base = getPath(_contextHandler.getBaseResource());
        if (_base == null)
            _base = Paths.get("/").toAbsolutePath();
        if (Files.exists(_base, NO_FOLLOW_LINKS))
            _base = _base.toRealPath(FOLLOW_LINKS);

        String[] protectedTargets = _contextHandler.getProtectedTargets();
        if (protectedTargets != null)
        {
            for (String s : protectedTargets)
                _protected.add(_base.getFileSystem().getPath(_base.toString(), s));
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        _base = null;
        _protected.clear();
    }

    @Override
    public boolean check(String pathInContext, Resource resource)
    {
        try
        {
            // do not allow any file separation characters in the URI
            if (File.separatorChar != '/' && pathInContext.indexOf(File.separatorChar) >= 0)
                return false;

            // The existence check resolves the symlinks.
            if (!resource.exists())
                return false;

            Path path = getPath(resource);
            if (path == null)
                return false;

            return check(pathInContext, path);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failed to check alias", t);
            return false;
        }
    }

    protected boolean check(String pathInContext, Path path)
    {
        // Allow any aliases (symlinks, 8.3, casing, etc.) so long as
        // the resulting real file is allowed.
        return isAllowed(getRealPath(path));
    }

    protected boolean isAllowed(Path path)
    {
        // If the resource doesn't exist we cannot determine whether it is protected so we assume it is.
        if (path != null && Files.exists(path))
        {
            // Walk the path parent links looking for the base resource, but failing if any steps are protected
            while (path != null)
            {
                // If the path is the same file as the base, then it is contained in the base and
                // is not protected.
                if (isSameFile(path, _base))
                    return true;

                // If the path is the same file as any protected resources, then it is protected.
                for (Path p : _protected)
                {
                    if (isSameFile(path, p))
                        return false;
                }

                // Walks up the aliased path name, not the real path name.
                // If WEB-INF is a link to /var/lib/webmeta then after checking
                // a URI of /WEB-INF/file.xml the parent is /WEB-INF and not /var/lib/webmeta
                path = path.getParent();
            }
        }

        return false;
    }

    protected boolean isSameFile(Path path1, Path path2)
    {
        if (Objects.equals(path1, path2))
            return true;
        try
        {
            if (Files.isSameFile(path1, path2))
                return true;
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ignored", t);
        }
        return false;
    }

    private static Path getRealPath(Path path)
    {
        if (path == null || !Files.exists(path))
            return null;
        try
        {
            path = path.toRealPath(FOLLOW_LINKS);
            if (Files.exists(path))
                return path;
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No real path for {}", path, e);
        }
        return null;
    }

    protected Path getPath(Resource resource)
    {
        try
        {
            if (resource instanceof PathResource)
                return ((PathResource)resource).getPath();
            return resource.getFile().toPath();
        }
        catch (Throwable t)
        {
            LOG.trace("getPath() failed", t);
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{base=%s,protected=%s}",
            this.getClass().getSimpleName(),
            hashCode(),
            _base,
            Arrays.asList(_contextHandler.getProtectedTargets()));
    }
}
