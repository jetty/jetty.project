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

package org.eclipse.jetty.deploy;

import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.FileID;

/**
 * Just some utility methods for Tests
 */
public class Util
{
    public static ContextHandler createContextHandler(String name)
    {
        String basename = FileID.getBasename(name);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/" + basename);
        return contextHandler;
    }

    public static ContextHandler createContextHandler(Path war)
    {
        ContextHandler contextHandler = new ContextHandler();

        String contextPath = war.getFileName().toString();

        if (FileID.isWebArchive(war))
        {
            // Context Path is the same as the archive.
            contextPath = FileID.getBasename(war);
        }

        // special case of archive named "root" is / context-path
        if (contextPath.equalsIgnoreCase("root"))
            contextPath = "/";

        // Ensure "/" is Prepended to all context paths.
        if (contextPath.charAt(0) != '/')
            contextPath = "/" + contextPath;

        // Ensure "/" is Not Trailing in context paths.
        if (contextPath.endsWith("/"))
            contextPath = contextPath.substring(0, contextPath.length() - 1);

        contextHandler.setContextPath(contextPath);
        return contextHandler;
    }
}
