//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

import javax.servlet.ServletContainerInitializer;

import org.apache.juli.logging.Log;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.apache.jsp.JuliLog;

module org.eclipse.jetty.apache.jsp
{
    exports org.eclipse.jetty.jsp;
    exports org.eclipse.jetty.apache.jsp;

    requires java.xml;
    requires org.eclipse.jetty.util;
    requires org.mortbay.apache.jasper;
    requires static javax.servlet.api;

    provides Log with JuliLog;
    provides ServletContainerInitializer with JettyJasperInitializer;
}
