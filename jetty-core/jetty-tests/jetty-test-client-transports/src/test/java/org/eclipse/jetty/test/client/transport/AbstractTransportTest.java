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

package org.eclipse.jetty.test.client.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public abstract class AbstractTransportTest
{
    protected Server server;

    @BeforeEach
    public void setup()
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    protected static Path newUnixDomainPath() throws IOException
    {
        String unixDomainDir = System.getProperty("jetty.unixdomain.dir", System.getProperty("java.io.tmpdir"));
        Path unixDomainFile = Files.createTempFile(Path.of(unixDomainDir), "jetty-", ".sock");
        Files.delete(unixDomainFile);
        return unixDomainFile;
    }
}
