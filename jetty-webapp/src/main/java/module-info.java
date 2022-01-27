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

module org.eclipse.jetty.webapp
{
    requires org.slf4j;

    requires transitive java.instrument;
    requires transitive org.eclipse.jetty.servlet;
    requires transitive org.eclipse.jetty.xml;

    exports org.eclipse.jetty.webapp;

    uses org.eclipse.jetty.webapp.Configuration;

    provides org.eclipse.jetty.webapp.Configuration with 
        org.eclipse.jetty.webapp.FragmentConfiguration,
        org.eclipse.jetty.webapp.JaasConfiguration,
        org.eclipse.jetty.webapp.JaspiConfiguration,
        org.eclipse.jetty.webapp.JettyWebXmlConfiguration,
        org.eclipse.jetty.webapp.JmxConfiguration,
        org.eclipse.jetty.webapp.JndiConfiguration,
        org.eclipse.jetty.webapp.JspConfiguration,
        org.eclipse.jetty.webapp.MetaInfConfiguration,
        org.eclipse.jetty.webapp.ServletsConfiguration,
        org.eclipse.jetty.webapp.WebAppConfiguration,
        org.eclipse.jetty.webapp.WebInfConfiguration,
        org.eclipse.jetty.webapp.WebXmlConfiguration;
}
