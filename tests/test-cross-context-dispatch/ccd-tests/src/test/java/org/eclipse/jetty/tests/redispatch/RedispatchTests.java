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

package org.eclipse.jetty.tests.redispatch;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class RedispatchTests extends AbstractRedispatchTest
{
    public WorkDir workDir;
    private JettyHomeTester.Run runStart;

    @AfterEach
    public void stopRun()
    {
        runStart.close();
    }

    /**
     * Test ee8 behavior if an HttpServletRequestWrapper messes with the
     * {@code getRequestURI()} method.
     * see {@code org.eclipse.jetty.tests.ccd.ee8.InternalRequestURIFilter}
     */
    @Test
    public void testEe8FilterWithAwkwardRequestURI() throws Exception
    {
        InitializedJettyBase jettyBase = new InitializedJettyBase(workDir);

        // Now add the filter to the webapp xml init
        String xml = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
            <Configure class="org.eclipse.jetty.ee8.webapp.WebAppContext">
              <Set name="contextPath">/ccd-ee8</Set>
              <Set name="war"><Property name="jetty.webapps" default="." />/ccd-ee8</Set>
              <Set name="crossContextDispatchSupported">true</Set>
              <Call name="addFilter">
                <Arg type="String">org.eclipse.jetty.tests.ccd.ee8.InternalRequestURIFilter</Arg>
                <Arg type="String">/*</Arg>
                <Arg>
                  <Call class="java.util.EnumSet" name="of">
                    <Arg><Get class="javax.servlet.DispatcherType" name="REQUEST"/></Arg>
                    <Arg><Get class="javax.servlet.DispatcherType" name="FORWARD"/></Arg>
                    <Arg><Get class="javax.servlet.DispatcherType" name="INCLUDE"/></Arg>
                  </Call>
                </Arg>
              </Call>
            </Configure>
            """;
        // Note: the InternalRequestURIFilter messes with the requestURI
        Files.writeString(jettyBase.jettyBase.resolve("webapps/ccd-ee8.xml"), xml, StandardCharsets.UTF_8);

        // Start Jetty instance
        String[] argsStart = {
            "jetty.http.port=" + jettyBase.httpPort
        };

        runStart = jettyBase.distribution.start(argsStart);
        assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

        ContentResponse response = client.newRequest("localhost", jettyBase.httpPort)
            .method(HttpMethod.GET)
            .headers((headers) ->
                headers.put("X-ForwardTo", "/dump/ee8")
            )
            .path("/ccd-ee8/forwardto/ee8")
            .send();

        String responseDetails = toResponseDetails(response);
        assertThat(responseDetails, response.getStatus(), is(HttpStatus.OK_200));

        Properties responseProps = new Properties();
        try (StringReader stringReader = new StringReader(response.getContentAsString()))
        {
            responseProps.load(stringReader);
        }

        dumpProperties(responseProps);

        assertProperty(responseProps, "request.dispatcherType", is("FORWARD"));
        assertProperty(responseProps, "request.requestURI", is("/internal/")); // the key change to look for
    }
}
