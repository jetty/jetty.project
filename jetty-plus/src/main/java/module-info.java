//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.webapp.Configuration;

module org.eclipse.jetty.plus
{
    exports org.eclipse.jetty.plus.annotation;
    exports org.eclipse.jetty.plus.jndi;
    exports org.eclipse.jetty.plus.security;
    exports org.eclipse.jetty.plus.webapp;

    requires org.eclipse.jetty.jndi;
    requires transitive org.eclipse.jetty.webapp;

    // Only required if using DataSourceLoginService.
    requires static java.sql;
    // Only required if using Transaction.
    requires static java.transaction;
    // Only required if using RunAs.
    requires static org.eclipse.jetty.servlet;

    provides Configuration with EnvConfiguration, PlusConfiguration;
}
