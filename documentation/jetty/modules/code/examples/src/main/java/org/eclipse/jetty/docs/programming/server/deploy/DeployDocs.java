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

package org.eclipse.jetty.docs.programming.server.deploy;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.deploy.Deployer;
import org.eclipse.jetty.deploy.DeploymentScanner;
import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.xml.EnvironmentBuilder;

@SuppressWarnings("unused")
public class DeployDocs
{
    public void simple() throws Exception
    {
        // tag::simple[]
        Server server = new Server();

        // ContextHandlerCollection is required by the deployer.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // Optional, shows the contexts deployed on the Server.
        server.setDefaultHandler(new DefaultHandler());

        // Create the deployer.
        Deployer deployer = new StandardDeployer(contexts);

        // Create the DeploymentScanner to monitor the webapps directory.
        DeploymentScanner scanner = new DeploymentScanner(server, deployer);
        scanner.setWebappsDirectories(List.of(Path.of("/path/to/webapps")));
        // Link the lifecycle of the DeploymentScanner to the Server.
        server.addBean(scanner);

        // Create an environment, with the name of your choice,
        // and no extra class-path or module-path.
        EnvironmentBuilder envBuilder = new EnvironmentBuilder("simple");
        Environment environment = envBuilder.build();

        // Tell the DeploymentScanner about the environment
        // it should use to deploy web applications.
        scanner.configureEnvironment(environment.getName());

        server.start();
        // end::simple[]
    }

    public void jettyStatic() throws Exception
    {
        // tag::static[]
        Server server = new Server();

        // ContextHandlerCollection is required by the deployer.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // Optional, shows the contexts deployed on the Server.
        server.setDefaultHandler(new DefaultHandler());

        // Create the deployer.
        Deployer deployer = new StandardDeployer(contexts);

        // Create the DeploymentScanner to monitor the webapps directory.
        DeploymentScanner scanner = new DeploymentScanner(server, deployer);
        scanner.setWebappsDirectories(List.of(Path.of("/path/to/webapps")));
        // Link the lifecycle of the DeploymentScanner to the Server.
        server.addBean(scanner);

        // Create the static environment.
        EnvironmentBuilder envBuilder = new EnvironmentBuilder("static");
        envBuilder.addClassPath("/path/to/jetty-staticapp-{jetty-version}.jar"); // <1>
        Environment environment = envBuilder.build();

        // Tell the DeploymentScanner about the environment, and configure it for deployment.
        DeploymentScanner.EnvironmentConfig envConfig = scanner.configureEnvironment(environment.getName()); // <2>
        envConfig.setDefaultContextHandlerClassName("org.eclipse.jetty.staticapp.StaticAppContext");

        server.start();
        // end::static[]
    }

    public void jettyCore() throws Exception
    {
        // tag::core[]
        Server server = new Server();

        // ContextHandlerCollection is required by the deployer.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // Optional, shows the contexts deployed on the Server.
        server.setDefaultHandler(new DefaultHandler());

        // Create the deployer.
        Deployer deployer = new StandardDeployer(contexts);

        // Create the DeploymentScanner to monitor the webapps directory.
        DeploymentScanner scanner = new DeploymentScanner(server, deployer);
        scanner.setWebappsDirectories(List.of(Path.of("/path/to/webapps")));
        // Link the lifecycle of the DeploymentScanner to the Server.
        server.addBean(scanner);

        // Create the core environment.
        EnvironmentBuilder envBuilder = new EnvironmentBuilder("core");
        envBuilder.addClassPath("/path/to/jetty-coreapp-{jetty-version}.jar"); // <1>
        Environment environment = envBuilder.build();

        // Tell the DeploymentScanner about the environment, and configure it for deployment.
        DeploymentScanner.EnvironmentConfig envConfig = scanner.configureEnvironment(environment.getName()); // <2>
        envConfig.setDefaultContextHandlerClassName("org.eclipse.jetty.coreapp.CoreAppContext");

        server.start();
        // end::core[]
    }

    public void jakarta() throws Exception
    {
        // tag::jakarta[]
        Server server = new Server();

        // ContextHandlerCollection is required by the deployer.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // Optional, shows the contexts deployed on the Server.
        server.setDefaultHandler(new DefaultHandler());

        // Create the deployer.
        Deployer deployer = new StandardDeployer(contexts);

        // Create the DeploymentScanner to monitor the webapps directory.
        DeploymentScanner scanner = new DeploymentScanner(server, deployer);
        scanner.setWebappsDirectories(List.of(Path.of("/path/to/webapps")));
        // Link the lifecycle of the DeploymentScanner to the Server.
        server.addBean(scanner);

        // Create the {ee-current} environment.
        EnvironmentBuilder envBuilder = new EnvironmentBuilder("{ee-current}");
        envBuilder.addClassPath("/path/to/jakarta.servlet-api-{servlet-current-version}.jar"); // <1>
        envBuilder.addClassPath("/path/to/jetty-{ee-current}-servlet-{jetty-version}.jar");
        envBuilder.addClassPath("/path/to/jetty-{ee-current}-webapp-{jetty-version}.jar");
        Environment environment = envBuilder.build();

        // Tell the DeploymentScanner about the environment, and configure it for deployment.
        DeploymentScanner.EnvironmentConfig envConfig = scanner.configureEnvironment(environment.getName());
        envConfig.setDefaultContextHandlerClassName("org.eclipse.jetty.{ee-current}.webapp.WebAppContext"); // <2>
        envConfig.setDefaultsDescriptor("/path/to/{ee-current}-default-web.xml"); // <3>
        // Other relevant Jakarta environment configurations.

        server.start();
        // end::jakarta[]
    }

    public void multi() throws Exception
    {
        // tag::multi[]
        Server server = new Server();

        // ContextHandlerCollection is required by the deployer.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // Optional, shows the contexts deployed on the Server.
        server.setDefaultHandler(new DefaultHandler());

        // Create the deployer.
        Deployer deployer = new StandardDeployer(contexts);

        // Create the DeploymentScanner to monitor the webapps directory.
        DeploymentScanner scanner = new DeploymentScanner(server, deployer);
        scanner.setWebappsDirectories(List.of(Path.of("/path/to/webapps")));
        // Link the lifecycle of the DeploymentScanner to the Server.
        server.addBean(scanner);

        // Create the core environment.
        EnvironmentBuilder coreEnvBuilder = new EnvironmentBuilder("core");
        coreEnvBuilder.addClassPath("/path/to/jetty-coreapp-{jetty-version}.jar");
        Environment coreEnv = coreEnvBuilder.build();

        // Create the {ee-current} environment.
        EnvironmentBuilder jakartaEnvBuilder = new EnvironmentBuilder("{ee-current}");
        jakartaEnvBuilder.addClassPath("/path/to/jakarta.servlet-api-{servlet-current-version}.jar");
        jakartaEnvBuilder.addClassPath("/path/to/jetty-{ee-current}-servlet-{jetty-version}.jar");
        jakartaEnvBuilder.addClassPath("/path/to/jetty-{ee-current}-webapp-{jetty-version}.jar");
        Environment jakartaEnv = jakartaEnvBuilder.build();

        // Tell the DeploymentScanner about the environments, and configure them for deployment.

        DeploymentScanner.EnvironmentConfig coreEnvConfig = scanner.configureEnvironment(coreEnv.getName());
        coreEnvConfig.setDefaultContextHandlerClassName("org.eclipse.jetty.coreapp.CoreAppContext");

        DeploymentScanner.EnvironmentConfig jakartaEnvConfig = scanner.configureEnvironment(jakartaEnv.getName());
        jakartaEnvConfig.setDefaultContextHandlerClassName("org.eclipse.jetty.{ee-current}.webapp.WebAppContext");
        jakartaEnvConfig.setDefaultsDescriptor("/path/to/{ee-current}-default-web.xml");
        // Other relevant Jakarta environment configurations.

        server.start();
        // end::multi[]
    }
}
