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

package org.eclipse.jetty.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Symlinks not supported on Windows")
public class SymlinkAllowedResourceAliasCheckerTest
{
    private Path rootPath;
    private Path symlinkPath;
    private ResourceFactory.LifeCycle resourceFactory;

    @BeforeEach
    public void setUp() throws Exception
    {
        rootPath = MavenTestingUtils.getTargetTestingPath(getClass().getSimpleName());
        FS.ensureEmpty(rootPath);

        Path file = rootPath.resolve("file.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        symlinkPath = rootPath.resolve("link.txt");
        try
        {
            Files.createSymbolicLink(symlinkPath, file.getFileName());
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            throw new TestAbortedException("Symlinks not supported", e);
        }

        resourceFactory = ResourceFactory.lifecycle();
        resourceFactory.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (resourceFactory != null)
            resourceFactory.stop();
        FS.ensureEmpty(rootPath);
    }

    @Test
    public void testBaseResourceResolvedAfterStart() throws Exception
    {
        AtomicReference<Resource> baseRef = new AtomicReference<>();
        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.clearAliasChecks();

        Server server = new Server();
        server.setHandler(context);
        server.start();

        SymlinkAllowedResourceAliasChecker checker = new SymlinkAllowedResourceAliasChecker(context, baseRef::get);
        context.addAliasCheck(checker);
        assertTrue(checker.isStarted());
        assertNull(checker.getBaseResource());

        baseRef.set(resourceFactory.newResource(rootPath));

        Resource resource = resourceFactory.newResource(symlinkPath);
        assertTrue(resource.isAlias());
        assertTrue(checker.checkAlias("/link.txt", resource));

        server.stop();
    }

    @Test
    public void testNullBaseResourceNotAllowed() throws Exception
    {
        AtomicReference<Resource> baseRef = new AtomicReference<>();
        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.clearAliasChecks();

        SymlinkAllowedResourceAliasChecker checker = new SymlinkAllowedResourceAliasChecker(context, baseRef::get);
        checker.start();

        Resource resource = resourceFactory.newResource(symlinkPath);
        assertTrue(resource.isAlias());
        assertFalse(checker.checkAlias("/link.txt", resource));
    }
}
