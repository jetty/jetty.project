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

package org.eclipse.jetty.ee10.jspc.plugin;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * This goal will compile jsps for a webapp so that they can be included in a
 * war.
 * <p>
 * At runtime, the plugin will use the jspc compiler to precompile jsps and tags.
 * </p>
 * <p>
 * Note that the same java compiler will be used as for on-the-fly compiled
 * jsps, which will be the Eclipse java compiler.
 * <p>
 * See <a href="https://jetty.org/docs/">Usage Guide</a> for
 * instructions on using this plugin.
 * </p>
 * Runs jspc compiler to produce .java and .class files
 */
@Mojo(name = "jspc", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = true)
public class JspcMojo extends org.eclipse.jetty.ee.jspc.plugin.JspcMojo
{
}
