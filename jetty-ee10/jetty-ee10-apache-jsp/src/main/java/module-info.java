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

module org.eclipse.jetty.ee10.apache.jsp
{
    requires java.xml;
    requires jetty.servlet.api;
    requires org.eclipse.jetty.util;
    requires org.mortbay.apache.jasper;
    requires org.slf4j;

    exports org.eclipse.jetty.ee10.apache.jsp;
    exports org.eclipse.jetty.ee10.jsp;

    provides org.apache.juli.logging.Log with
        org.eclipse.jetty.ee10.apache.jsp.JuliLog;

    provides jakarta.servlet.ServletContainerInitializer with
        org.eclipse.jetty.ee10.apache.jsp.JettyJasperInitializer;
}
