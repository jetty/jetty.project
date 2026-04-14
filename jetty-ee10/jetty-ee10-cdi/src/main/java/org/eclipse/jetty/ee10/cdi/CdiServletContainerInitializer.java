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

package org.eclipse.jetty.ee10.cdi;

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;

/**
 * <p>A {@link ServletContainerInitializer} that introspects for a CDI API
 * implementation within a web application and applies an integration
 * mode if CDI is found.  CDI integration modes can be selected per webapp with
 * the "org.eclipse.jetty.cdi" init parameter or default to the mode set by the
 * "org.eclipse.jetty.cdi" server attribute.  Supported modes are:</p>
 * <dl>
 * <dt>CdiSpiDecorator</dt>
 *     <dd>Jetty will call the CDI SPI within the webapp to decorate objects (default).</dd>
 * <dt>CdiDecoratingLister</dt>
 *     <dd>The webapp may register a decorator on the context attribute
 *     "org.eclipse.jetty.cdi.decorator".</dd>
 * </dl>
 *
 * @see AnnotationConfiguration.ServletContainerInitializerOrdering
 */
public class CdiServletContainerInitializer extends org.eclipse.jetty.ee.cdi.CdiServletContainerInitializer
{
}
