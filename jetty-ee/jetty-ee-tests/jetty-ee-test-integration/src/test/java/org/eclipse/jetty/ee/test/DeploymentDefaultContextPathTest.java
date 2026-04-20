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

package org.eclipse.jetty.ee.test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.DeploymentScanner;
import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.ee.webapp.WebAppContext;
import org.eclipse.jetty.ee.webapp.WebDescriptor;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@Isolated
@ExtendWith(WorkDirExtension.class)
public class DeploymentDefaultContextPathTest
{
    public WorkDir workDir;

    private Server server;
    private LocalConnector connector;

    private void startServer(Path webappsDir) throws Exception
    {
        server = new Server();

        connector = new LocalConnector(server);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        StandardDeployer deployer = new StandardDeployer(contexts);
        server.addBean(deployer);

        DeploymentScanner deploymentScanner = new DeploymentScanner(server, deployer);
        deploymentScanner.addWebappsDirectory(webappsDir);
        deploymentScanner.setScanInterval(1);

        Environment.ensure("ee", WebAppContext.class);
        DeploymentScanner.EnvironmentConfig environmentConfig = deploymentScanner.configureEnvironment("ee");
        environmentConfig.setDefaultContextHandlerClass(WebAppContext.class);

        server.addBean(deploymentScanner);

        server.setHandler(contexts);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    /**
     * Test that deploys a simple war (no XML).
     * The basename in the /webapps/ directory is just `test`.
     * The {@code <war>!/WEB-INF/web.xml} has a default-context-path of `/test-default`.
     * The deployed context-path should be `/test-default`
     */
    @Test
    public void testDefaultContextPath() throws Exception
    {
        Path base = workDir.getEmptyPathDir();
        Path webappsDir = base.resolve("webapps");
        FS.ensureDirExists(webappsDir);

        // Create webapp in webapps directory.
        Path war = webappsDir.resolve("test.war");
        createWarFile(war, "/test-default");

        startServer(webappsDir);

        String rawRequest = """
            GET /test-default/ HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("<body>Test</body>"));
    }

    /**
     * Test that deploys a simple war, with XML.
     * The basename in the /webapps/ directory is just `test`.
     * The {@code <war>!/WEB-INF/web.xml} has a default-context-path of `/test-other`.
     * The /webapps/test.xml sets the context-path to `/test-alt`
     * The deployed context-path should be `/test-alt`
     */
    @Test
    public void testXmlOverridesDefaultContextPath() throws Exception
    {
        Path base = workDir.getEmptyPathDir();
        Path webappsDir = base.resolve("webapps");
        FS.ensureDirExists(webappsDir);

        // Create webapp in webapps directory.
        Path war = webappsDir.resolve("test.war");
        createWarFile(war, "/test-other");

        // Create xml that sets a different context-path
        Path xml = webappsDir.resolve("test.xml");
        String xmlText = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure id="wac" class="org.eclipse.jetty.ee.webapp.WebAppContext">
              <Set name="contextPath">/test-alt</Set>
              <Set name="war"><Property name="jetty.webapps" default="." />/test.war</Set>
              <Set name="extractWAR">false</Set>
            </Configure>
            """;
        Files.writeString(xml, xmlText, StandardCharsets.UTF_8);

        startServer(webappsDir);

        String rawRequest = """
            GET /test-alt/ HTTP/1.1
            Host: local
            Connection: close
            
            """;
        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("<body>Test</body>"));
    }

    private void createWarFile(Path warFile, String defaultContextPath) throws IOException
    {
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            Path webinf = root.resolve("WEB-INF");
            FS.ensureDirExists(webinf);

            Path webXml = root.resolve("WEB-INF/web.xml");
            String webXmlText = WebDescriptor.WEB_APP_ELEMENT + """
                  <display-name>EE Test WebApp</display-name>
                  <default-context-path>%s</default-context-path>
                </web-app>
                """.formatted(defaultContextPath);
            Files.writeString(webXml, webXmlText, StandardCharsets.UTF_8);

            Path indexHtml = root.resolve("index.html");
            Files.writeString(indexHtml, "<html><body>Test</body></html>", StandardCharsets.UTF_8);
        }
    }
}
