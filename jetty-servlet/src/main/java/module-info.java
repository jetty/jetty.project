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

module org.eclipse.jetty.servlet
{
    requires org.slf4j;

    requires transitive org.eclipse.jetty.security;

    // Only required if using IntrospectorCleaner.
    requires static java.desktop;
    // Only required if using StatisticsServlet.
    requires static java.management;
    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;
    requires static org.eclipse.jetty.util.ajax;

    exports org.eclipse.jetty.servlet;
    exports org.eclipse.jetty.servlet.listener;

    exports org.eclipse.jetty.servlet.jmx to
         org.eclipse.jetty.jmx;
}
