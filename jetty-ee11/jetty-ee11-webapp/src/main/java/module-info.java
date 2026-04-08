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

module org.eclipse.jetty.ee11.webapp
{
    requires org.slf4j;

    requires transitive java.instrument;
    requires transitive org.eclipse.jetty.session;
    requires transitive org.eclipse.jetty.ee11.servlet;
    requires transitive org.eclipse.jetty.xml;
    requires transitive org.eclipse.jetty.ee.webapp;

    exports org.eclipse.jetty.ee11.webapp;

    uses org.eclipse.jetty.ee11.webapp.Configuration;

    provides org.eclipse.jetty.ee11.webapp.Configuration with
        org.eclipse.jetty.ee11.webapp.FragmentConfiguration,
        org.eclipse.jetty.ee11.webapp.JaasConfiguration,
        org.eclipse.jetty.ee11.webapp.JaspiConfiguration,
        org.eclipse.jetty.ee11.webapp.JettyWebXmlConfiguration,
        org.eclipse.jetty.ee11.webapp.JmxConfiguration,
        org.eclipse.jetty.ee11.webapp.JndiConfiguration,
        org.eclipse.jetty.ee11.webapp.JspConfiguration,
        org.eclipse.jetty.ee11.webapp.MetaInfConfiguration,
        org.eclipse.jetty.ee11.webapp.ServletsConfiguration,
        org.eclipse.jetty.ee11.webapp.WebAppConfiguration,
        org.eclipse.jetty.ee11.webapp.WebInfConfiguration,
        org.eclipse.jetty.ee11.webapp.WebXmlConfiguration;
}
