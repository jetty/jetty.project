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

module org.eclipse.jetty.ee.webapp
{
    requires org.slf4j;

    requires transitive java.instrument;
    requires transitive org.eclipse.jetty.session;
    requires transitive org.eclipse.jetty.ee.servlet;
    requires transitive org.eclipse.jetty.xml;
    requires transitive org.eclipse.jetty.ee.webapp;

    exports org.eclipse.jetty.ee.webapp;

    uses org.eclipse.jetty.ee.webapp.Configuration;

    provides org.eclipse.jetty.ee.webapp.Configuration with
        org.eclipse.jetty.ee.webapp.FragmentConfiguration,
        org.eclipse.jetty.ee.webapp.JaasConfiguration,
        org.eclipse.jetty.ee.webapp.JaspiConfiguration,
        org.eclipse.jetty.ee.webapp.JettyWebXmlConfiguration,
        org.eclipse.jetty.ee.webapp.JmxConfiguration,
        org.eclipse.jetty.ee.webapp.JndiConfiguration,
        org.eclipse.jetty.ee.webapp.JspConfiguration,
        org.eclipse.jetty.ee.webapp.MetaInfConfiguration,
        org.eclipse.jetty.ee.webapp.ServletsConfiguration,
        org.eclipse.jetty.ee.webapp.WebAppConfiguration,
        org.eclipse.jetty.ee.webapp.WebInfConfiguration,
        org.eclipse.jetty.ee.webapp.WebXmlConfiguration;
}
