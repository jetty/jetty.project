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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.servlet.http.HttpServlet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractFileContentServlet extends HttpServlet
{
    protected byte[] loadContentFileBytes(final String fileName) throws IOException
    {
        String relPath = fileName;
        relPath = relPath.replaceFirst("^/context/", "");
        relPath = relPath.replaceFirst("^/", "");

        String realPath = getServletContext().getRealPath(relPath);
        assertNotNull(realPath, "Unable to find real path for " + relPath);

        Path realFile = Paths.get(realPath);
        assertTrue(Files.exists(realFile), "Content File should exist: " + realFile);

        return Files.readAllBytes(realFile);
    }
}
