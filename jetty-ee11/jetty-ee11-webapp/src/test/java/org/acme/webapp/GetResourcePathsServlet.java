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

package org.acme.webapp;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class GetResourcePathsServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        collectResourcePaths("/").forEach(resp.getWriter()::println);
        resp.getWriter().flush();
    }

    private Set<String> collectResourcePaths(String path)
    {
        Set<String> allResourcePaths = new LinkedHashSet<>();
        Set<String> pathsForPath = getServletContext().getResourcePaths(path);
        if (pathsForPath != null)
        {
            for (String resourcePath : pathsForPath)
            {
                allResourcePaths.add(resourcePath);
                allResourcePaths.addAll(collectResourcePaths(resourcePath));
            }
        }
        return allResourcePaths;
    }
}
