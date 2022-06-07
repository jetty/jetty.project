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

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;

public class AsyncRestServer
{
    public static void main(String[] args)
        throws Exception
    {
        // Find the async-reset webapp based on common IDE working directories
        // TODO import webapp as maven artifact
        Path home = FileSystems.getDefault().getPath(System.getProperty("jetty.home", ".")).toAbsolutePath();
        Path war = home.resolve("../async-rest-webapp/target/async-rest/");
        if (!Files.exists(war))
            war = home.resolve("examples/async-rest/async-rest-webapp/target/async-rest/");
        if (!Files.exists(war))
            throw new IllegalArgumentException("Cannot find async-rest webapp");

        // Build a demo server
        Server server = new Server(Integer.getInteger("jetty.http.port", 8080).intValue());
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar(war.toAbsolutePath().toString());
        server.setHandler(webapp);

        server.start();
        server.join();
    }
}
