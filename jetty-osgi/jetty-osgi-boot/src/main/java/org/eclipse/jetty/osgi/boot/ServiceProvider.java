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

package org.eclipse.jetty.osgi.boot;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.ServiceReference;

/**
 * ServiceProvider
 *
 * Jetty DeploymentManager Provider api for webapps or ContextHandlers that are discovered as OSGi services.
 */
public interface ServiceProvider
{
    public boolean serviceAdded(ServiceReference ref, ContextHandler handler) throws Exception;

    public boolean serviceRemoved(ServiceReference ref, ContextHandler handler) throws Exception;
}
