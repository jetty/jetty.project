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

module org.eclipse.jetty.ee9.webapp
{
    requires org.slf4j;

    requires transitive java.instrument;
    requires transitive org.eclipse.jetty.ee9.servlet;
    requires transitive org.eclipse.jetty.xml;

    exports org.eclipse.jetty.ee9.webapp;

    uses org.eclipse.jetty.ee9.webapp.Configuration;

    provides org.eclipse.jetty.ee9.webapp.Configuration with 
        org.eclipse.jetty.ee9.webapp.FragmentConfiguration,
        org.eclipse.jetty.ee9.webapp.JaasConfiguration,
        org.eclipse.jetty.ee9.webapp.JaspiConfiguration,
        org.eclipse.jetty.ee9.webapp.JettyWebXmlConfiguration,
        org.eclipse.jetty.ee9.webapp.JmxConfiguration,
        org.eclipse.jetty.ee9.webapp.JndiConfiguration,
        org.eclipse.jetty.ee9.webapp.JspConfiguration,
        org.eclipse.jetty.ee9.webapp.MetaInfConfiguration,
        org.eclipse.jetty.ee9.webapp.ServletsConfiguration,
        org.eclipse.jetty.ee9.webapp.WebAppConfiguration,
        org.eclipse.jetty.ee9.webapp.WebInfConfiguration,
        org.eclipse.jetty.ee9.webapp.WebXmlConfiguration;
}
