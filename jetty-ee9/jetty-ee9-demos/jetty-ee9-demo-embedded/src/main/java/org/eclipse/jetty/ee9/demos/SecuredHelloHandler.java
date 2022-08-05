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
import java.util.Collections;

import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.HashLoginService;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.ee9.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.security.Constraint;

public class SecuredHelloHandler
{
    public static Server createServer(int port) throws FileNotFoundException
    {
        // Create a basic jetty server object that will listen on port 8080.
        // Note that if you set this to port 0 then a randomly available port
        // will be assigned that you can either look in the logs for the port,
        // or programmatically obtain it for use in test cases.
        Server server = new Server(port);

        // Since this example is for our test webapp, we need to setup a
        // LoginService so this shows how to create a very simple hashmap based
        // one. The name of the LoginService needs to correspond to what is
        // configured a webapp's web.xml and since it has a lifecycle of its own
        // we register it as a bean with the Jetty server object so it can be
        // started and stopped according to the lifecycle of the server itself.
        // In this example the name can be whatever you like since we are not
        // dealing with webapp realms.
        String realmResourceName = "etc/realm.properties";
        Resource realmResource = ResourceFactory.of(server).newClassPathResource("etc/realm.properties");
        if (realmResource == null)
            throw new FileNotFoundException("Unable to find " + realmResourceName);

        LoginService loginService = new HashLoginService("MyRealm", realmResource);
        server.addBean(loginService);

        // A security handler is a jetty handler that secures content behind a
        // particular portion of a url space. The ConstraintSecurityHandler is a
        // more specialized handler that allows matching of urls to different
        // constraints. The server sets this as the first handler in the chain,
        // effectively applying these constraints to all subsequent handlers in
        // the chain.
        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        server.setHandler(security);

        // This constraint requires authentication and in addition that an
        // authenticated user be a member of a given set of roles for
        // authorization purposes.
        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"user", "admin"});

        // Binds a url pattern with the previously created constraint. The roles
        // for this constraint mapping are mined from the Constraint itself
        // although methods exist to declare and bind roles separately as well.
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/*");
        mapping.setConstraint(constraint);

        // First you see the constraint mapping being applied to the handler as
        // a singleton list, however you can passing in as many security
        // constraint mappings as you like so long as they follow the mapping
        // requirements of the servlet api. Next we set a BasicAuthenticator
        // instance which is the object that actually checks the credentials
        // followed by the LoginService which is the store of known users, etc.
        security.setConstraintMappings(Collections.singletonList(mapping));
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        // The Hello Handler is the handler we are securing so we create one,
        // and then set it as the handler on the
        // security handler to complain the simple handler chain.
        HelloHandler hh = new HelloHandler();

        // chain the hello handler into the security handler
        security.setHandler(hh);

        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        // Start things up!
        server.start();

        // The use of server.join() the will make the current thread join and
        // wait until the server is done executing.
        server.join();
    }
}
