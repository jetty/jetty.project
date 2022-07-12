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

package org.eclipse.jetty.ee10.demos;

import java.io.FileNotFoundException;
import java.net.URL;
import java.nio.file.Path;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.servlet.security.HashLoginService;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;

public class OneWebAppWithJsp
{
    public static Server createServer(int port) throws FileNotFoundException
    {
        // Create a basic jetty server object that will listen on port 8080.
        // Note that if you set this to port 0 then
        // a randomly available port will be assigned that you can either look
        // in the logs for the port,
        // or programmatically obtain it for use in test cases.
        Server server = new Server(port);

        // The WebAppContext is the entity that controls the environment in
        // which a web application lives and breathes.
        // In this example the context path is being set to "/" so it
        // is suitable for serving root context
        // requests and then we see it setting the location of the war.
        // A whole host of other configurations are
        // available, ranging from configuring to support annotation scanning in
        // the webapp (through PlusConfiguration), to choosing where
        // the webapp will unpack itself.
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        Path warFile = JettyDemos.find("demo-jsp-webapp/target/demo-jsp-webapp-@VER@.war");
        webapp.setWarResource(Resource.newResource(warFile));
        webapp.setExtractWAR(true);

        // This webapp will use jsps and jstl. We need to enable the
        // AnnotationConfiguration in order to correctly
        // set up the jsp container
        webapp.addConfiguration(new AnnotationConfiguration());

        // Set the ContainerIncludeJarPattern so that jetty examines these
        // container-path jars for tlds, web-fragments etc.
        // If you omit the jar that contains the jstl .tlds, the jsp engine will
        // scan for them instead.
        webapp.setAttribute(
            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/jetty-jakarta-servlet-api-[^/]*\\.jar$|.*/jakarta.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$");

        // A WebAppContext is a ContextHandler as well so it needs to be set to
        // the server so it is aware of where to
        // send the appropriate requests.
        server.setHandler(webapp);

        // Configure a LoginService.
        // Since this example is for our test webapp, we need to setup a
        // LoginService so this shows how to create a very simple hashmap based
        // one. The name of the LoginService needs to correspond to what is
        // configured in the webapp's web.xml and since it has a lifecycle of
        // its own we register it as a bean with the Jetty server object so it
        // can be started and stopped according to the lifecycle of the server
        // itself.
        String realmResourceName = "etc/realm.properties";
        ClassLoader classLoader = OneWebAppWithJsp.class.getClassLoader();
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

        // Start things up!
        server.start();

        server.dumpStdErr();

        // The use of server.join() the will make the current thread join and
        // wait until the server is done executing.
        server.join();
    }
}
