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

module org.eclipse.jetty.plus
{
    requires transitive org.eclipse.jetty.security;
    requires org.eclipse.jetty.util;
    requires org.slf4j;

    requires transitive java.naming;
    // Only required if using DataSourceLoginService.
    requires static transitive java.sql;

    exports org.eclipse.jetty.plus.annotation;
    exports org.eclipse.jetty.plus.jndi;
    exports org.eclipse.jetty.plus.security;
}
