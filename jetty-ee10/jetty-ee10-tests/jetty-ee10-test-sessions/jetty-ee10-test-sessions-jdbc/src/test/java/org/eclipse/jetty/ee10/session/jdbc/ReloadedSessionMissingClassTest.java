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

package org.eclipse.jetty.ee10.session.jdbc;

import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.session.SessionTestSupport;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.JdbcTestHelper;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReloadedSessionMissingClassTest
 */
//TODO
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(WorkDirExtension.class)
public class ReloadedSessionMissingClassTest
{

    private String sessionTableName;

    @BeforeEach
    public void setupSessionTableName() throws Exception
    {
        this.sessionTableName = getClass().getSimpleName() + "_" + System.nanoTime();
        JdbcTestHelper.prepareTables(sessionTableName);
    }

    @Test
    public void testSessionReloadWithMissingClass(WorkDir workDir) throws Exception
    {
        Path unpackedWarDir = workDir.getEmptyPathDir();
        String contextPath = "/foo";

        File webInfDir = new File(unpackedWarDir.toFile(), "WEB-INF");
        webInfDir.mkdir();

        File webXml = new File(webInfDir, "web.xml");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"http://java.sun.com/xml/ns/j2ee\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd\"\n" +
                "         version=\"2.4\">\n" +
                "\n" +
                "<session-config>\n" +
                " <session-timeout>1</session-timeout>\n" +
                "</session-config>\n" +
                "</web-app>";
        FileWriter w = new FileWriter(webXml);
        w.write(xml);
        w.close();

        File foobarJar = MavenTestingUtils.getTestResourceFile("foobar.jar");
        File foobarNOfooJar = MavenTestingUtils.getTestResourceFile("foobarNOfoo.jar");

        URL[] foobarUrls = new URL[]{foobarJar.toURI().toURL()};
        URL[] barUrls = new URL[]{foobarNOfooJar.toURI().toURL()};

        URLClassLoader loaderWithFoo = new URLClassLoader(foobarUrls, Thread.currentThread().getContextClassLoader());
        URLClassLoader loaderWithoutFoo = new URLClassLoader(barUrls, Thread.currentThread().getContextClassLoader());

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = JdbcTestHelper.newSessionDataStoreFactory(sessionTableName, false);
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server1 = new SessionTestSupport(0, SessionTestSupport.DEFAULT_MAX_INACTIVE, SessionTestSupport.DEFAULT_SCAVENGE_SEC, cacheFactory, storeFactory);

        WebAppContext webApp = server1.addWebAppContext(unpackedWarDir.toFile().getCanonicalPath(), contextPath);
        webApp.getSessionHandler().getSessionCache().setRemoveUnloadableSessions(true);
        webApp.setClassLoader(loaderWithFoo);
        webApp.addServlet("Bar", "/bar");
        server1.start();
        int port1 = server1.getPort();
        try (StacklessLogging stackless = new StacklessLogging(ReloadedSessionMissingClassTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform one request to server1 to create a session
                ContentResponse response = client.GET("http://localhost:" + port1 + contextPath + "/bar?action=set");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = (String)webApp.getServletContext().getAttribute("foo");
                assertNotNull(sessionId);

                //Stop the webapp
                webApp.stop();

                webApp.setClassLoader(loaderWithoutFoo);

                //restart webapp
                webApp.start();

                Request request = client.newRequest("http://localhost:" + port1 + contextPath + "/bar?action=get");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                String afterStopSessionId = (String)webApp.getServletContext().getAttribute("foo.session");
                Boolean fooPresent = (Boolean)webApp.getServletContext().getAttribute("foo.present");
                assertFalse(fooPresent);
                assertNotNull(afterStopSessionId);
                assertTrue(!afterStopSessionId.equals(sessionId));
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        JdbcTestHelper.shutdown(sessionTableName);
    }
}
