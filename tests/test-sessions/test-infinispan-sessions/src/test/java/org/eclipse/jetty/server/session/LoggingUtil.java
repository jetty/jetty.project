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

package org.eclipse.jetty.server.session;

public final class LoggingUtil
{
    /**
     * It's easier to setup logging in code for this test project,
     * then it is to setup the various system properties and files for every test
     * execution (maven, CI, and IDE).
     */
    public static void init()
    {
        // Wire up jboss logging (used by infinispan) to slf4j
        System.setProperty("org.jboss.logging.provider", "slf4j");

        // Wire up java.util.logging (used by hibernate, infinispan, and others) to slf4j.
        if (!org.slf4j.bridge.SLF4JBridgeHandler.isInstalled())
        {
            org.slf4j.bridge.SLF4JBridgeHandler.install();
        }
    }
}
