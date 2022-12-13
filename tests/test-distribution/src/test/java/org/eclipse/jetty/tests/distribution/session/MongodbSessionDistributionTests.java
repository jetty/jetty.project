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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.DockerImageName;

/**
 *  
 */
public class MongodbSessionDistributionTests extends AbstractSessionDistributionTests
{
    private static final Logger LOGGER = LoggerFactory.getLogger(MongodbSessionDistributionTests.class);

    private static final Logger MONGO_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.session.mongo");

    private static final int MONGO_PORT = 27017;

    final String imageName = "mongo:" + System.getProperty("mongo.docker.version", "3.2.20");

    final MongoDBContainer mongoDBContainer =
            new MongoDBContainer(DockerImageName.parse(imageName))
                    .withLogConsumer(new Slf4jLogConsumer(MONGO_LOG));

    private String host;
    private int port;

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        mongoDBContainer.start();
        host = mongoDBContainer.getHost();
        port = mongoDBContainer.getMappedPort(MONGO_PORT);
    }

    @Override
    public void stopExternalSessionStorage() throws Exception
    {
        mongoDBContainer.stop();
    }

    @Override
    public void configureExternalSessionStorage(Path jettyBase) throws Exception
    {
        // no op
    }
    
    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Collections.emptyList();
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-mongo";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Arrays.asList(
                "jetty.session.mongo.host=" + host,
                "jetty.session.mongo.port=" + port
            );
    }

}
