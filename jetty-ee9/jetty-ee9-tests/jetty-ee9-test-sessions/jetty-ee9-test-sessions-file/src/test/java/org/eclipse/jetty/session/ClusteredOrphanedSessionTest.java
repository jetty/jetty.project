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

package org.eclipse.jetty.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.jetty.ee9.session.AbstractClusteredOrphanedSessionTest;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusteredOrphanedSessionTest
 */
@ExtendWith(WorkDirExtension.class)
public class ClusteredOrphanedSessionTest extends AbstractClusteredOrphanedSessionTest
{
    public WorkDir workDir;
    public Path _storeDir;

    @BeforeEach
    public void before() throws Exception
    {
        _storeDir = Objects.requireNonNull(workDir.getEmptyPathDir());
        assertTrue(Files.exists(_storeDir), "Path must exist: " + _storeDir);
        assertTrue(Files.isDirectory(_storeDir), "Path must be a directory: " + _storeDir);
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(_storeDir.toFile());
        return storeFactory;
    }

    @Test
    @Override
    public void testOrphanedSession() throws Exception
    {
        super.testOrphanedSession();
    }
}
