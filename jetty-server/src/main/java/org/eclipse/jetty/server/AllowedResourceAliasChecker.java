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

    public AllowedResourceAliasChecker(ContextHandler contextHandler)
    {
        _contextHandler = contextHandler;
    }

    @Override
    public boolean check(String uri, Resource resource)
    {
        try
        {
            String baseResourcePath = _contextHandler.getBaseResource().getFile().getCanonicalPath();
            String resourcePath = resource.getFile().getCanonicalPath();
            if (!resourcePath.startsWith(baseResourcePath))
                return false;

            for (String s : _contextHandler.getProtectedTargets())
            {
                String protectedTarget = new File(_contextHandler.getBaseResource().getFile(), s).getCanonicalPath();
                if (StringUtil.startsWithIgnoreCase(resourcePath, protectedTarget))
                {
                    if (resourcePath.length() == protectedTarget.length())
                        return false;

                    // Check that the target prefix really is a path segment.
                    char c = resourcePath.charAt(protectedTarget.length());
                    if (c == File.separatorChar)
                        return false;
                }
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            return false;
        }

        // TODO: Check symlink targets if flag is set.
        return true;
    }
}