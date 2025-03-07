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

package org.eclipse.jetty.test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerTestBase
{
    public static Path getResource(String path) throws Exception
    {
        URL url = AliasCheckerMultipleResourceTest.class.getClassLoader().getResource(".");
        assertNotNull(url);
        return new File(url.toURI()).toPath().resolve(path);
    }

    public static void delete(Path path)
    {
        if (path != null)
            IO.delete(path.toFile());
    }

    public static ResourceHandler newResourceHandler(Path resourceBase)
    {
        Resource resource = toResource(resourceBase);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(resource);
        return resourceHandler;
    }

    public static ResourceHandler newResourceHandler(Resource resourceBase)
    {
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(resourceBase);
        return resourceHandler;
    }

    public static Resource toResource(Path path)
    {
        return ResourceFactory.root().newResource(path);
    }

    public static void createSymbolicLink(Path symlinkFile, Path target) throws IOException
    {
        delete(symlinkFile);
        Files.createDirectories(symlinkFile.getParent());
        Files.createSymbolicLink(symlinkFile, target).toFile().deleteOnExit();
    }

    public static void setAliasCheckers(ContextHandler contextHandler, AliasCheck... aliasChecks)
    {
        contextHandler.clearAliasChecks();
        if (aliasChecks != null)
        {
            for (AliasCheck aliasCheck : aliasChecks)
            {
                if (aliasCheck != null)
                    contextHandler.addAliasCheck(aliasCheck);
            }
        }
    }
}
