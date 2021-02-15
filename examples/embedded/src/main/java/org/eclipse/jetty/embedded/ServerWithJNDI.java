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

import java.nio.file.Path;
import java.util.Properties;
import javax.naming.NamingException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * ServerWithJNDI
 */
public class ServerWithJNDI
{
    public static Server createServer(int port) throws NamingException
    {
        // Create the server
        Server server = new Server(port);

        // Enable parsing of jndi-related parts of web.xml and jetty-env.xml
        Configuration.ClassList classlist = Configuration.ClassList
            .setServerDefault(server);
        classlist.addAfter("org.eclipse.jetty.webapp.FragmentConfiguration",
            "org.eclipse.jetty.plus.webapp.EnvConfiguration",
            "org.eclipse.jetty.plus.webapp.PlusConfiguration");

        // Create a WebApp
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        Path testJndiWar = JettyDistribution.resolve("demo-base/webapps/test-jndi.war");
        webapp.setWarResource(new PathResource(testJndiWar));
        server.setHandler(webapp);

        // Register new transaction manager in JNDI
        // At runtime, the webapp accesses this as java:comp/UserTransaction
        new org.eclipse.jetty.plus.jndi.Transaction(
            new com.acme.MockUserTransaction());

        // Define an env entry with Server scope.
        // At runtime, the webapp accesses this as java:comp/env/woggle
        // This is equivalent to putting an env-entry in web.xml:
        // <env-entry>
        // <env-entry-name>woggle</env-entry-name>
        // <env-entry-type>java.lang.Integer</env-entry-type>
        // <env-entry-value>4000</env-entry-value>
        // </env-entry>
        new org.eclipse.jetty.plus.jndi.EnvEntry(server, "woggle", 4000, false);

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
        new org.eclipse.jetty.plus.jndi.EnvEntry(webapp, "wiggle", 100d, true);

        // Register a reference to a mail service scoped to the webapp.
        // This must be linked to the webapp by an entry in web.xml:
        // <resource-ref>
        // <res-ref-name>mail/Session</res-ref-name>
        // <res-type>javax.mail.Session</res-type>
        // <res-auth>Container</res-auth>
        // </resource-ref>
        // At runtime the webapp accesses this as java:comp/env/mail/Session
        org.eclipse.jetty.jndi.factories.MailSessionReference mailref =
            new org.eclipse.jetty.jndi.factories.MailSessionReference();
        mailref.setUser("CHANGE-ME");
        mailref.setPassword("CHANGE-ME");
        Properties props = new Properties();
        props.put("mail.smtp.auth", "false");
        props.put("mail.smtp.host", "CHANGE-ME");
        props.put("mail.from", "CHANGE-ME");
        props.put("mail.debug", "false");
        mailref.setProperties(props);
        new org.eclipse.jetty.plus.jndi.Resource(webapp, "mail/Session", mailref);

        // Register a mock DataSource scoped to the webapp
        // This must be linked to the webapp via an entry in web.xml:
        // <resource-ref>
        // <res-ref-name>jdbc/mydatasource</res-ref-name>
        // <res-type>javax.sql.DataSource</res-type>
        // <res-auth>Container</res-auth>
        // </resource-ref>
        // At runtime the webapp accesses this as
        // java:comp/env/jdbc/mydatasource
        new org.eclipse.jetty.plus.jndi.Resource(
            webapp, "jdbc/mydatasource", new com.acme.MockDataSource());
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
