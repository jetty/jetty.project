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

import org.eclipse.jetty.ee10.servlet.security.Authenticator;

module org.eclipse.jetty.ee10.servlet
{
    requires org.slf4j;

    requires transitive jetty.servlet.api;
    requires transitive org.eclipse.jetty.server;
    requires transitive org.eclipse.jetty.session;

    // Only required if using IntrospectorCleaner.
    requires static java.desktop;
    // Only required if using StatisticsServlet.
    requires static java.management;
    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;
    requires static org.eclipse.jetty.util.ajax;
    // Only required if using SPNEGO.
    requires static java.security.jgss;
    // Only required if using JDBCLoginService.
    requires static java.sql;

    exports org.eclipse.jetty.ee10.servlet;
    exports org.eclipse.jetty.ee10.servlet.listener;
    exports org.eclipse.jetty.ee10.servlet.security;
    exports org.eclipse.jetty.ee10.servlet.security.authentication;
    exports org.eclipse.jetty.ee10.servlet.util;
    exports org.eclipse.jetty.ee10.servlet.writer;

    exports org.eclipse.jetty.ee10.servlet.jmx to
         org.eclipse.jetty.jmx;

    uses Authenticator.Factory;
}
