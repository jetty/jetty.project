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
import java.nio.file.Path;
import javax.naming.NamingException;

import org.eclipse.jetty.ee9.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * ServerWithJNDI
 */
public class ServerWithJNDI
{
    public static Server createServer(int port) throws NamingException, FileNotFoundException
    {
        // Create the server
        Server server = new Server(port);

        // Create a WebApp
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        Path testJndiWar = JettyDemos.find("demo-jndi-webapp/target/demo-jndi-webapp-@VER@.war");
        webapp.setWarResource(ResourceFactory.of(webapp).newResource(testJndiWar));
        server.setHandler(webapp);

        // Enable parsing of jndi-related parts of web.xml and jetty-env.xml
        webapp.addConfiguration(new EnvConfiguration(), new PlusConfiguration());

        // Register new transaction manager in JNDI
        // At runtime, the webapp accesses this as java:comp/UserTransaction
        new org.eclipse.jetty.ee9.plus.jndi.Transaction(
            "ee9",
            new org.example.MockUserTransaction());

        // Define an env entry with ee9 scope.
        // At runtime, the webapp accesses this as java:comp/env/woggle
        // This is equivalent to putting an env-entry in web.xml:
        // <env-entry>
        // <env-entry-name>woggle</env-entry-name>
        // <env-entry-type>java.lang.Integer</env-entry-type>
        // <env-entry-value>4000</env-entry-value>
        // </env-entry>
        new org.eclipse.jetty.ee9.plus.jndi.EnvEntry("ee9", "woggle", 4000, false);

        // Define an env entry with webapp scope.
        // At runtime, the webapp accesses this as java:comp/env/wiggle
        // This is equivalent to putting a web.xml entry in web.xml:
        // <env-entry>
        // <env-entry-name>wiggle</env-entry-name>
        // <env-entry-value>100</env-entry-value>
        // <env-entry-type>java.lang.Double</env-entry-type>
        // </env-entry>
        // Note that the last arg of "true" means that this definition for
        // "wiggle" would override an entry of the
        // same name in web.xml
        new org.eclipse.jetty.ee9.plus.jndi.EnvEntry(webapp, "wiggle", 100d, true);

        // Register a mock DataSource scoped to the webapp
        // This must be linked to the webapp via an entry in web.xml:
        // <resource-ref>
        // <res-ref-name>jdbc/mydatasource</res-ref-name>
        // <res-type>javax.sql.DataSource</res-type>
        // <res-auth>Container</res-auth>
        // </resource-ref>
        // At runtime the webapp accesses this as
        // java:comp/env/jdbc/mydatasource
        new org.eclipse.jetty.ee9.plus.jndi.Resource(
            webapp, "jdbc/mydatasource", new org.example.MockDataSource());
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        server.start();
        server.join();
    }
}
