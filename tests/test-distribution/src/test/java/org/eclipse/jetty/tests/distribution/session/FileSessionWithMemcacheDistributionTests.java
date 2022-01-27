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

package org.eclipse.jetty.tests.distribution.session;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

/**
 *
 */
public class FileSessionWithMemcacheDistributionTests extends AbstractSessionDistributionTests
{

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSessionWithMemcacheDistributionTests.class);
    private static final Logger MEMCACHED_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.session.memcached");

    private GenericContainer memcached;

    private String host;
    private int port;

    @Override
    @BeforeEach
    public void prepareJettyHomeTester() throws Exception
    {
        memcached =
                new GenericContainer("memcached:" + System.getProperty("memcached.docker.version", "1.6.6"))
                        .withExposedPorts(11211)
                        .withLogConsumer(new Slf4jLogConsumer(MEMCACHED_LOG));
        memcached.start();
        this.host = memcached.getContainerIpAddress();
        this.port = memcached.getMappedPort(11211);
        super.prepareJettyHomeTester();
    }
    
    @Override
    public void configureExternalSessionStorage(Path jettyBase) throws Exception
    {
        // no op
    }
    
    @Override
    public void startExternalSessionStorage() throws Exception
    {
        // no op
    }

    @Override
    public Map<String, String> env()
    {
        return Map.of("MEMCACHE_PORT_11211_TCP_ADDR", host, "MEMCACHE_PORT_11211_TCP_PORT", Integer.toString(port));
    }

    @Override
    public void stopExternalSessionStorage() throws Exception
    {
        memcached.stop();
    }

    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Collections.singletonList("session-data-cache=xmemcached");
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-file,session-store-cache";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Collections.singletonList("session-data-cache=xmemcached");
    }

}
