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

package org.eclipse.jetty.ee10.maven.plugin;

/**
 * MavenWebAppContext
 * <p>
 * Extends the WebAppContext to specialize for the maven environment. We pass in
 * the list of files that should form the classpath for the webapp when
 * executing in the plugin, and any jetty-env.xml file that may have been
 * configured.
 */
public class MavenWebAppContext extends org.eclipse.jetty.ee.maven.plugin.MavenWebAppContext
{
}
