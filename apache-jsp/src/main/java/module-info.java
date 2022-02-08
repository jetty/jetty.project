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

module org.eclipse.jetty.apache.jsp
{
    requires java.xml;
    requires jetty.servlet.api;
    requires org.eclipse.jetty.util;
    requires org.mortbay.apache.jasper;
    requires org.slf4j;

    exports org.eclipse.jetty.apache.jsp;
    exports org.eclipse.jetty.jsp;

    provides org.apache.juli.logging.Log with
        org.eclipse.jetty.apache.jsp.JuliLog;

    provides javax.servlet.ServletContainerInitializer with
        org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
}
