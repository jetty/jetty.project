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

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.ee10.cdi.CdiConfiguration;
import org.eclipse.jetty.ee10.cdi.CdiServletContainerInitializer;
import org.eclipse.jetty.ee10.webapp.Configuration;

module org.eclipse.jetty.ee10.cdi
{
    requires org.eclipse.jetty.ee10.annotations;

    requires transitive org.eclipse.jetty.ee10.servlet;
    requires transitive org.eclipse.jetty.ee10.webapp;

    exports org.eclipse.jetty.ee10.cdi;

    provides ServletContainerInitializer with CdiServletContainerInitializer;
    provides Configuration with CdiConfiguration;
}
