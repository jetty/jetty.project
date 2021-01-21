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

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import javax.naming.NamingException;

import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.jndi.NamingDump;
import org.eclipse.jetty.plus.jndi.Resource;
import org.eclipse.jetty.plus.jndi.Transaction;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * ServerWithAnnotations
 */
public class ServerWithAnnotations
{
    public static Server createServer(int port) throws NamingException, FileNotFoundException
    {
        // Create the server
        Server server = new Server(port);

        // Enable parsing of jndi-related parts of web.xml and jetty-env.xml
        Configuration.ClassList classlist = Configuration.ClassList
            .setServerDefault(server);
        classlist.addAfter("org.eclipse.jetty.webapp.FragmentConfiguration",
            "org.eclipse.jetty.plus.webapp.EnvConfiguration",
            "org.eclipse.jetty.plus.webapp.PlusConfiguration");
        classlist.addBefore(
            "org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
            "org.eclipse.jetty.annotations.AnnotationConfiguration");
        // Create a WebApp
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        File warFile = JettyDistribution.resolve("demo-base/webapps/test-spec.war").toFile();
        webapp.setWar(warFile.getAbsolutePath());
        webapp.setAttribute(
            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/javax.servlet-[^/]*\\.jar$|.*/servlet-api-[^/]*\\.jar$");
        server.setHandler(webapp);

        // Register new transaction manager in JNDI
        // At runtime, the webapp accesses this as java:comp/UserTransaction
        new Transaction(new com.acme.MockUserTransaction());

        // Define an env entry with webapp scope.
        // THIS ENTRY IS OVERRIDDEN BY THE ENTRY IN jetty-env.xml
        new EnvEntry(webapp, "maxAmount", 100d, true);

        // Register a mock DataSource scoped to the webapp
        new Resource(server, "jdbc/mydatasource", new com.acme.MockDataSource());

        // Add JNDI context to server for dump
        server.addBean(new NamingDump());

        // Configure a LoginService
        String realmResourceName = "etc/realm.properties";
        ClassLoader classLoader = ServerWithAnnotations.class.getClassLoader();
        URL realmProps = classLoader.getResource(realmResourceName);
        if (realmProps == null)
            throw new FileNotFoundException("Unable to find " + realmResourceName);

        HashLoginService loginService = new HashLoginService();
        loginService.setName("Test Realm");
        loginService.setConfig(realmProps.toExternalForm());
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
