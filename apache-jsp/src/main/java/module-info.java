//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import javax.servlet.ServletContainerInitializer;

import org.apache.juli.logging.Log;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.apache.jsp.JuliLog;

module org.eclipse.jetty.apache.jsp
{
    exports org.eclipse.jetty.apache.jsp;
    exports org.eclipse.jetty.jsp;

    requires java.xml;
    requires jetty.servlet.api;
    requires org.eclipse.jetty.util;
    requires org.mortbay.apache.jasper;
    requires org.slf4j;

    provides Log with JuliLog;
    provides ServletContainerInitializer with JettyJasperInitializer;
}
