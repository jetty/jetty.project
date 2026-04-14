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

package org.eclipse.jetty.ee10.osgi.annotations;

/**
 * Extend the {@link org.eclipse.jetty.ee10.annotations.AnnotationConfiguration} to support OSGi:
 * Look for annotations inside WEB-INF/lib and also in the fragments and required bundles.
 * Discover them using a scanner adapted to OSGi.
 */
public class AnnotationConfiguration extends org.eclipse.jetty.ee.osgi.annotations.AnnotationConfiguration
{
    public AnnotationConfiguration()
    {
        super();
    }
}
