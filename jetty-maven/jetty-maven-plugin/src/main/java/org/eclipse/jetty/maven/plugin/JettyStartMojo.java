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
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 *  <p>
 *  This goal is similar to the jetty:run goal in that it it starts jetty on an unassembled webapp, 
 *  EXCEPT that it is designed to be bound to an execution inside your pom. Thus, this goal does NOT
 *  run a parallel build cycle, so you must be careful to ensure that you bind it to a phase in 
 *  which all necessary generated files and classes for the webapp have been created.
 *  </p>
 * <p>
 * This goal will NOT scan for changes in either the webapp project or any scanTargets or scanTargetPatterns.
 * </p>
 * <p>
 *  You can configure this goal to run your webapp either in-process with maven, or forked into a new process, or deployed into a
 *  jetty distribution.
 *  </p>
 */
@Mojo(name = "start", requiresDependencyResolution = ResolutionScope.TEST)
public class JettyStartMojo extends AbstractUnassembledWebAppMojo
{
    //
}
