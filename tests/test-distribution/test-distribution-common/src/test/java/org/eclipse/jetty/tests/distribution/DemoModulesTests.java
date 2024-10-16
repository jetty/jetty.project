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

package org.eclipse.jetty.tests.distribution;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class DemoModulesTests extends AbstractJettyHomeTest
{
    private static Stream<Arguments> provideEnvironmentsToTest()
    {
        String envsToTest = System.getProperty("environmentsToTest", "ee8,ee9,ee10");
        return Arrays.stream(envsToTest.split(",")).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testAuthentication(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);
        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart =
            {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/dump/auth/admin/info");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));

                Fields fields = new Fields();
                fields.put("j_username", "admin");
                fields.put("j_password", "admin");
                response = client.FORM(baseURI + "/j_security_check", fields);
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Dump Servlet"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testDemoAddHiddenClasses(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue(), "Exit value");

            try (JettyHomeTester.Run runListConfig = distribution.start("--list-config"))
            {
                assertTrue(runListConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
                assertEquals(0, runListConfig.getExitValue(), "Exit value");
                // Example of what we expect
                // jetty.webapp.addHiddenClasses = org.eclipse.jetty.logging.,${jetty.home.uri}/lib/logging/,org.slf4j.,${jetty.base.uri}/lib/bouncycastle/
                String addServerKey = " jetty.webapp.addHiddenClasses = ";
                String addServerClasses = runListConfig.getLogs().stream()
                    .filter(s -> s.startsWith(addServerKey))
                    .findFirst()
                    .orElseThrow(() ->
                        new NoSuchElementException("Unable to find [" + addServerKey + "]"));
                assertThat("'jetty.webapp.addHiddenClasses' entry count",
                    addServerClasses.split(",").length,
                    greaterThan(1));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJspDump(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demo-jsp", env)
        };

        String baseURI = "http://localhost:%d/%s-demo-jsp".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/dump.jsp");

                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("PathInfo"));
                assertThat(response.getContentAsString(), not(containsString("<%")));


            }
        }
    }
    
    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJaasDemo(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
                "--add-modules=http," + toEnvironment("demo-jaas", env)
        };
        
        String baseURI = "http://localhost:%d/%s-test-jaas".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = 
            {
                    "jetty.http.port=" + httpPort,
                    "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/auth.html");
                Fields fields = new Fields();
                fields.put("j_username", "me");
                fields.put("j_password", "me");
                response = client.FORM(baseURI + "/j_security_check", fields);
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("SUCCESS!"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJstlDemo(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
                "--add-modules=http," + toEnvironment("demo-jsp", env)
        };

        String baseURI = "http://localhost:%d/%s-demo-jsp".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort,
                    "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/jstl.jsp");

                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), containsString("5"));
                assertThat(response.getContentAsString(), containsString("10"));
                assertThat(response.getContentAsString(), not(containsString("<c:forEach")));


            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    @Tag("external")
    public void testAsyncRest(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demo-async-rest", env)
        };

        String baseURI = "http://localhost:%d/%s-demo-async-rest".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response;

                response = client.GET(baseURI + "/testSerial?items=kayak");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Blocking: kayak"));

                response = client.GET(baseURI + "/testSerial?items=mouse,beer,gnome");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Blocking: mouse,beer,gnome"));

                response = client.GET(baseURI + "/testAsync?items=kayak");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Asynchronous: kayak"));

                response = client.GET(baseURI + "/testAsync?items=mouse,beer,gnome");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Asynchronous: mouse,beer,gnome"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testSpec(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();

                String baseURI = "http://localhost:%d/%s-test-spec".formatted(httpPort, env);

                //test the async listener
                ContentResponse response = client.POST(baseURI + "/asy/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
                assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

                //test the servlet 3.1/4 features
                response = client.POST(baseURI + "/test/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
                assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

                //test dynamic jsp
                response = client.POST(baseURI + "/dynamicjsp/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Programmatically Added Jsp File"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJPMS(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);
        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart =
            {
                "--jpms",
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/hello");
                assertEquals(HttpStatus.OK_200, response.getStatus());

                response = client.GET(baseURI + "/dump/info");
                assertEquals(HttpStatus.OK_200, response.getStatus());

                // TODO this should not fail!
                // response = client.GET(baseURI + "/jetty-dir.css");
                // assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testSessionDump(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            int httpPort = Tester.freePort();
            int sslPort = Tester.freePort();
            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS),
                        String.join("", runStart.getLogs()));

                String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);

                startHttpClient();
                client.setFollowRedirects(true);
                ContentResponse response = client.GET(baseURI + "/session/");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));

                // Submit "New Session"
                Fields form = new Fields();
                form.add("Action", "New Session");
                response = client.POST(baseURI + "/session/")
                    .body(new FormRequestContent(form))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                String content = response.getContentAsString();
                assertThat("Content", content, containsString("<b>test:</b> value<br/>"));
                assertThat("Content", content, containsString("<b>WEBCL:</b> {}<br/>"));

                // Last Location
                URI location = response.getRequest().getURI();

                // Submit a "Set" for a new entry in the cookie
                form = new Fields();
                form.add("Action", "Set");
                form.add("Name", "Zed");
                form.add("Value", "[alpha]");
                response = client.POST(location)
                    .body(new FormRequestContent(form))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                content = response.getContentAsString();
                assertThat("Content", content, containsString("<b>Zed:</b> [alpha]<br/>"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testRewrite(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            int httpPort = Tester.freePort();
            int sslPort = Tester.freePort();

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS),
                    String.join("", runStart.getLogs()));

                String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);

                startHttpClient();
                client.setFollowRedirects(true);
                ContentResponse response = client.GET(baseURI + "/rewrite/");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));

                String content = response.getContentAsString();
                assertThat("Content", content, containsString("Links to test the RewriteHandler"));
            }
        }
    }

    @Test
    public void testDemoHandler() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
                "--add-modules=http,demo-handler"
        };

        String baseURI = "http://localhost:%d/demo-handler/".formatted(httpPort);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort,
                    "jetty.ssl.port=" + sslPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI);

                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Hello World"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testStaticContent(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig =
        {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart =
            {
                 "jetty.http.port=" + httpPort,
                 "jetty.ssl.port=" + sslPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient();

                String rootURI = "http://localhost:%d".formatted(httpPort);
                String demoJettyURI = "%s/%s-test".formatted(rootURI, env);

                ContentResponse response;

                for (String welcome : new String[] {"", "/", "/index.html"})
                {
                    response = client.GET(rootURI + welcome);
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    assertThat(response.getContentAsString(), containsString("Welcome to Jetty 12"));

                    response = client.GET(demoJettyURI + welcome);
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    assertThat(response.getContentAsString(), containsString("Eclipse Jetty Demo Webapp"));
                }

                for (String directory : new String[] {rootURI + "/", demoJettyURI + "/", demoJettyURI + "/rewrite/"})
                {
                    response = client.GET(directory + "jetty-dir.css");
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                }

                response = client.GET(rootURI + "/favicon.ico");
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJettyDemo(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort,
                "jetty.server.dumpAfterStart=true"
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient();
                String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);

                ContentResponse response = client.POST(baseURI + "/dump/info").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Dump Servlet"));
                assertThat(response.getContentAsString(), containsString("context-override-example:&nbsp;</th><td>a context value"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testDebugLogModule(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();
        int sslPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env) + ",debuglog"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.ssl.port=" + sslPort,
                "jetty.server.dumpAfterStart=true"
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient();
                String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);

                ContentResponse response = client.POST(baseURI + "/dump/info").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                Path jettyLogs = jettyBase.resolve("logs");
                assertTrue(Files.isDirectory(jettyLogs));
                try (Stream<Path> files = Files.list(jettyLogs))
                {
                    assertTrue(files.anyMatch(p -> p.toFile().getName().endsWith(".debug.log")));
                }
            }
        }
    }
}
