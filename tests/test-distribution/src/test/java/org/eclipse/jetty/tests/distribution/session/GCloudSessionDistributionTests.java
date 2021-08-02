//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.DatastoreEmulatorContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 *
 */
public class GCloudSessionDistributionTests extends AbstractSessionDistributionTests
{

    private static final Logger LOGGER = LoggerFactory.getLogger(GCloudSessionDistributionTests.class);
    private static final Logger GCLOUD_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.session.gcloudLogs");

    public DatastoreEmulatorContainer emulator =
            new CustomDatastoreEmulatorContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk:316.0.0-emulators"))
                    .withLogConsumer(new Slf4jLogConsumer(GCLOUD_LOG));

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk");

    private static final String CMD = "gcloud beta emulators datastore start --project test-project --host-port 0.0.0.0:8081 --consistency=1.0";
    private static final int HTTP_PORT = 8081;

    String host;

    public static class CustomDatastoreEmulatorContainer extends DatastoreEmulatorContainer
    {
        public CustomDatastoreEmulatorContainer(DockerImageName dockerImageName)
        {
            super(dockerImageName);

            dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

            withExposedPorts(HTTP_PORT);
            setWaitStrategy(Wait.forHttp("/").forStatusCode(200));
            withCommand("/bin/sh", "-c", CMD);
        }
    }

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        emulator.start();

        //work out if we're running locally or not: if not local, then the host passed to
        //DatastoreOptions must be prefixed with a scheme
        String endPoint = emulator.getEmulatorEndpoint();
        InetAddress hostAddr = InetAddress.getByName(new URL("http://" + endPoint).getHost());
        LOGGER.info("endPoint: {} ,hostAddr.isAnyLocalAddress(): {},hostAddr.isLoopbackAddress(): {}",
                endPoint,
                hostAddr.isAnyLocalAddress(),
                hostAddr.isLoopbackAddress());
        if (hostAddr.isAnyLocalAddress() || hostAddr.isLoopbackAddress())
            host = endPoint;
        else
            host = "http://" + endPoint;
    }

    @Override
    public void stopExternalSessionStorage() throws Exception
    {
        emulator.stop();
    }

    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Arrays.asList(
                "jetty.session.gcloud.host=" + host,
                "jetty.session.gcloud.projectId=foobar"
        );
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-gcloud";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Arrays.asList(
                "jetty.session.gcloud.host=" + host,
                "jetty.session.gcloud.projectId=foobar"
            );
    }

}
