//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.embedded;

import java.net.URI;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@ExtendWith(WorkDirExtension.class)
public class OneServletContextJmxStatsTest extends AbstractEmbeddedTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = OneServletContextJmxStats.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetDumpViaPathInfo() throws Exception
    {
        URI uri = server.getURI().resolve("/dump/something");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody,
            allOf(
                containsString("DumpServlet"),
                containsString("servletPath=/dump"),
                containsString("pathInfo=/something")
            )
        );
    }

    @Test
    public void testJmxConnectStatsPresent() throws Exception
    {
        MBeanContainer mbeanContainer = server.getBean(MBeanContainer.class);
        MBeanServer mbeanServer = mbeanContainer.getMBeanServer();

        String domain = ConnectionStatistics.class.getPackage().getName();
        Set<ObjectName> mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":type=connectionstatistics,*"), null);
        ObjectName connStatsName = mbeanNames.stream().findFirst().orElseThrow(AssertionFailedError::new);
        ObjectInstance mbeanConnStats = mbeanServer.getObjectInstance(connStatsName);
        Number connections = (Number)mbeanServer.getAttribute(connStatsName, "connections");
        assertThat("stats[connections]", connections, is(notNullValue()));
        assertThat("stats[connections]", connections.longValue(), greaterThanOrEqualTo(0L));
    }
}
