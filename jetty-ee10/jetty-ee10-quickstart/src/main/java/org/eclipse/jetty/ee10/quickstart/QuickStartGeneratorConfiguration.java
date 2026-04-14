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

package org.eclipse.jetty.ee10.quickstart;

import org.eclipse.jetty.ee.quickstart.QuickStartConfiguration;

/**
 * QuickStartGeneratorConfiguration
 * <p>
 * Generate an effective web.xml from a WebAppContext, including all components
 * from web.xml, web-fragment.xmls annotations etc.
 * <p>
 * If generating quickstart for a different java platform than the current running
 * platform, then the org.eclipse.jetty.ee10.annotations.javaTargetPlatform attribute
 * should be set on the Context with the platform number of the target JVM (eg 8).
 */
public class QuickStartGeneratorConfiguration extends QuickStartConfiguration
{
    public QuickStartGeneratorConfiguration()
    {
        super();
    }
}
