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

package org.eclipse.jetty.maven.plugin;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jetty.maven.AbstractWebAppMojo;

/**
 * This goal stops a running instance of jetty.
 *
 * The <b>stopPort</b> and <b>stopKey</b> parameters can be used to
 * configure which jetty to stop.
 */
@Mojo(name = "stop")
public class JettyStopMojo extends AbstractWebAppMojo
{
    /**
     * Max time in seconds that the plugin will wait for confirmation that jetty has stopped.
     */
    @Parameter
    protected int stopWait;

}
