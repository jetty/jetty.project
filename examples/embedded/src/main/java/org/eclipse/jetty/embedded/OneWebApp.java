//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

public class OneWebApp
{
    public static void main(String[] args) throws Exception
    {
        // Create a basic jetty server object that will listen on port 8080. Note that if you set this to port 0 then
        // a randomly available port will be assigned that you can either look in the logs for the port,
        // or programmatically obtain it for use in test cases.
        Server server = new Server(8080);
        
        // Setup JMX
        MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        // The WebAppContext is the entity that controls the environment in which a web application lives and
        // breathes. In this example the context path is being set to "/" so it is suitable for serving root context
        // requests and then we see it setting the location of the war. A whole host of other configurations are
        // available, ranging from configuring to support annotation scanning in the webapp (through
        // PlusConfiguration) to choosing where the webapp will unpack itself.
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar("../../jetty-distribution/target/distribution/demo-base/webapps/test.war");

        // A WebAppContext is a ContextHandler as well so it needs to be set to the server so it is aware of where to
        // send the appropriate requests.
        server.setHandler(webapp);

        // Configure a LoginService
        // Since this example is for our test webapp, we need to setup a LoginService so this shows how to create a
        // very simple hashmap based one. The name of the LoginService needs to correspond to what is configured in
        // the webapp's web.xml and since it has a lifecycle of its own we register it as a bean with the Jetty
        // server object so it can be started and stopped according to the lifecycle of the server itself.
        HashLoginService loginService = new HashLoginService();
        loginService.setName("Test Realm");
        loginService.setConfig("src/test/resources/realm.properties");
        server.addBean(loginService);

        // Start things up! By using the server.join() the server thread will join with the current thread.
        // See "http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#join()" for more details.
        server.start();
        server.join();
    }
}
