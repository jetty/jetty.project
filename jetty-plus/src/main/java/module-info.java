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
    requires org.slf4j;

    // Only required if using DataSourceLoginService.
    requires static java.sql;
    // Only required if using Transaction.
    requires static java.transaction;
    // Only required if using RunAs.
    requires static org.eclipse.jetty.servlet;

    provides Configuration with EnvConfiguration, PlusConfiguration;
}
