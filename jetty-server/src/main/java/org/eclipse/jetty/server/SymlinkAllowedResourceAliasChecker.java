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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * An extension of {@link AllowedResourceAliasChecker} which only allows resources if they are symlinks.
 */
public class SymlinkAllowedResourceAliasChecker extends AllowedResourceAliasChecker
{
    private static final Logger LOG = Log.getLogger(SymlinkAllowedResourceAliasChecker.class);
    private final ContextHandler _contextHandler;

    /**
     * @param contextHandler the context handler to use.
     */
    public SymlinkAllowedResourceAliasChecker(ContextHandler contextHandler)
    {
        super(contextHandler, false);
        _contextHandler = contextHandler;
    }

    @Override
    public boolean check(String uri, Resource resource)
    {
        try
        {
            // Check the resource is allowed to be accessed.
            if (!super.check(uri, resource))
                return false;

            // Only approve resource if it is accessed by a symbolic link.
            Path resourcePath = resource.getFile().toPath();
            if (Files.isSymbolicLink(resourcePath))
                return true;

            // TODO: If base resource contains symlink then this will always return true.
            //  But we don't want to deny all paths if the resource base is symbolically linked.
            if (super.hasSymbolicLink(resourcePath))
                return true;
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            return false;
        }

        return false;
    }
}
