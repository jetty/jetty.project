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

import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee10.webapp.JaasConfiguration;
import org.eclipse.jetty.ee10.webapp.JaspiConfiguration;
import org.eclipse.jetty.ee10.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee10.webapp.JmxConfiguration;
import org.eclipse.jetty.ee10.webapp.JndiConfiguration;
import org.eclipse.jetty.ee10.webapp.JspConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.ServletsConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppConfiguration;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;

module org.eclipse.jetty.ee10.webapp
{
    requires org.slf4j;

    requires transitive java.instrument;
    requires transitive org.eclipse.jetty.ee10.handler;
    requires transitive org.eclipse.jetty.ee10.security;
    requires transitive org.eclipse.jetty.session;
    requires transitive org.eclipse.jetty.ee10.servlet;
    requires transitive org.eclipse.jetty.xml;

    exports org.eclipse.jetty.ee10.webapp;

    uses Configuration;

    provides Configuration with
        FragmentConfiguration,
        JaasConfiguration,
        JaspiConfiguration,
        JettyWebXmlConfiguration,
        JmxConfiguration,
        JndiConfiguration,
        JspConfiguration,
        MetaInfConfiguration,
        ServletsConfiguration,
        WebAppConfiguration,
        WebInfConfiguration,
        WebXmlConfiguration;
}
