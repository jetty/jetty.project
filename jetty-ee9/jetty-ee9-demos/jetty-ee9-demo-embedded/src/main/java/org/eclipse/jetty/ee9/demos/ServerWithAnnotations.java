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

package org.eclipse.jetty.ee9.demos;

import java.io.FileNotFoundException;
import java.net.URL;
import java.nio.file.Path;
import javax.naming.NamingException;

import org.eclipse.jetty.ee9.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee9.plus.jndi.EnvEntry;
import org.eclipse.jetty.ee9.plus.jndi.NamingDump;
import org.eclipse.jetty.ee9.plus.jndi.Resource;
import org.eclipse.jetty.ee9.plus.jndi.Transaction;
import org.eclipse.jetty.ee9.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee9.security.HashLoginService;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * ServerWithAnnotations
 */
public class ServerWithAnnotations
{
    public static Server createServer(int port) throws NamingException, FileNotFoundException
    {
        // Create the server
        Server server = new Server(port);

        // Create a WebApp
        WebAppContext webapp = new WebAppContext();
        ResourceFactory resourceFactory = webapp.getResourceFactory();

        // Enable parsing of jndi-related parts of web.xml and jetty-env.xml
        webapp.addConfiguration(new EnvConfiguration(), new PlusConfiguration(), new AnnotationConfiguration());

        webapp.setContextPath("/");
        Path warFile = JettyDemos.find("ee9-demo-spec/ee9-demo-spec-webapp/target/ee9-demo-spec-webapp-@VER@.war");
        webapp.setWar(warFile.toString());
        webapp.setAttribute(
            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/jetty-jakarta-servlet-api-[^/]*\\.jar$");
        server.setHandler(webapp);

        // Register new transaction manager in JNDI
        // At runtime, the webapp accesses this as java:comp/UserTransaction
        new Transaction(new org.example.MockUserTransaction());

        // Define an env entry with webapp scope.
        // THIS ENTRY IS OVERRIDDEN BY THE ENTRY IN jetty-env.xml
        new EnvEntry(webapp, "maxAmount", 100d, true);

        // Register a mock DataSource scoped to the webapp
        new Resource(server, "jdbc/mydatasource", new org.example.MockDataSource());

        // Add JNDI context to server for dump
        server.addBean(new NamingDump());

        // Configure a LoginService
        String realmResourceName = "etc/realm.properties";

        org.eclipse.jetty.util.resource.Resource realmResource = resourceFactory.newClassPathResource(realmResourceName);
        if (realmResource == null)
            throw new FileNotFoundException("Unable to find " + realmResourceName);

        HashLoginService loginService = new HashLoginService();
        loginService.setName("Test Realm");
        loginService.setConfig(realmResource);
        server.addBean(loginService);
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        server.start();
        server.dumpStdErr();
        server.join();
    }
}
