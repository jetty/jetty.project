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
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee.common.ServletApiVersion;
import org.eclipse.jetty.ee.servlet.ServletChannel;
import org.eclipse.jetty.ee.test.servlets.AlwaysUnsupportedServlet;
import org.eclipse.jetty.ee.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class ErrorHandlingViaJspTest
{
    public WorkDir workDir;
    private Server server;
    private HttpClient client;

    public void startServer(WebAppContext context) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        contexts.addHandler(context);

        server.start();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
    }

    @Test
    public void testErrorHandlingViaJsp() throws Exception
    {
        Path webappDir = workDir.getEmptyPathDir().resolve("webapp");
        FS.ensureDirExists(webappDir);

        Path webinfDir = Files.createDirectory(webappDir.resolve("WEB-INF"));
        Path classesDir = Files.createDirectory(webinfDir.resolve("classes"));

        copyClass(AlwaysUnsupportedServlet.class, classesDir);

        Path webxml = webinfDir.resolve("web.xml");

        Files.writeString(webxml, """
            <web-app %s
              metadata-complete="false">
              <display-name>Sample WebApp</display-name>
            
              <servlet>
                <servlet-name>always-throw-an-exception</servlet-name>
                <servlet-class>%s</servlet-class>
              </servlet>
            
              <servlet-mapping>
                <servlet-name>always-throw-an-exception</servlet-name>
                <url-pattern>/toss/*</url-pattern>
              </servlet-mapping>
            
              <error-page>
                <exception-type>java.lang.UnsupportedOperationException</exception-type>
                <location>/error.jsp</location>
              </error-page>
            </web-app>
            """.formatted(ServletApiVersion.V5_0.getWebXmlAttributes(), AlwaysUnsupportedServlet.class.getName()), UTF_8);

        Files.writeString(webappDir.resolve("error.jsp"), """
            <%@ page language="java" contentType="text/html; charset=utf-8"
                     pageEncoding="utf-8" %>
            (From JSP Error Handler)
            Request.Params       : <%= request.getParameterMap() %>
            ERROR_EXCEPTION      : <%= request.getAttribute("jakarta.servlet.error.exception") %>
            ERROR_EXCEPTION_TYPE : <%= request.getAttribute("jakarta.servlet.error.exception_type") %>
            ERROR_MESSAGE        : <%= request.getAttribute("jakarta.servlet.error.message") %>
            ERROR_REQUEST_URI    : <%= request.getAttribute("jakarta.servlet.error.request_uri") %>
            ERROR_SERVLET_NAME   : <%= request.getAttribute("jakarta.servlet.error.servlet_name") %>
            ERROR_STATUS_CODE    : <%= request.getAttribute("jakarta.servlet.error.status_code") %>
            <% response.setStatus(418); %>
            """, StandardCharsets.UTF_8);

        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        Resource warResource = webAppContext.getResourceFactory().newResource(webappDir);
        webAppContext.setWarResource(warResource);
        startServer(webAppContext);

        URI serverURI = server.getURI();

        Fields form = new Fields();

        for (int i = 0; i < 200; i++)
        {
            form.add("n" + i, "helo" + i);
        }

        try (StacklessLogging ignore = new StacklessLogging(ServletChannel.class))
        {
            ContentResponse response = client.newRequest(serverURI.resolve("/toss/form"))
                .method(HttpMethod.POST)
                .body(new FormRequestContent(form))
                .send();

            // Ensure that handling of UnsupportedOperationException is done by /error.jsp and not the default ErrorHandler.
            assertThat(response.getContentAsString(), containsString("(From JSP Error Handler)"));
            assertThat(response.getStatus(), Matchers.is(HttpStatus.IM_A_TEAPOT_418));
        }
    }

    private void copyClass(Class<?> clazz, Path classesDir) throws IOException
    {
        URI srcUri = TypeUtil.getLocationOfClass(clazz);
        assertNotNull(srcUri, "Unable to find class: " + clazz.getName());
        if ("file".equals(srcUri.getScheme()))
        {
            String classRef = TypeUtil.toClassReference(clazz);
            Path srcFile = Path.of(srcUri).resolve(classRef);
            Path destFile = classesDir.resolve(classRef);
            FS.ensureDirExists(destFile.getParent());
            Files.copy(srcFile, destFile);
        }
    }
}
