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

package org.eclipse.jetty.ee.common;

public enum EnterpriseEditionVersion
{
    /**
     * Unable to determine the EE version in use.
     */
    unknown(-99),
    /**
     * EE10 is in use.
     */
    ee10(10),
    /**
     * EE11 is in use.
     */
    ee11(11),
    /**
     * EE12 is in use.
     */
    ee12(12);

    public static final EnterpriseEditionVersion currentVersion = initEnterpriseEditionVersion();

    public static EnterpriseEditionVersion getEnterpriseEditionVersion()
    {
        return currentVersion;
    }

    private final int version;

    EnterpriseEditionVersion(int version)
    {
        this.version = version;
    }

    public int version()
    {
        return version;
    }

    private static EnterpriseEditionVersion initEnterpriseEditionVersion()
    {
        try
        {
            // TODO: EE versioning is not tied to Servlet spec versioning.
            //       It is very possible for a EE version to increase WITHOUT
            //       The Servlet spec updating too.
            return switch (ServletApiVersion.getServletApiVersion())
            {
                case v6_0 -> ee10;
                case v6_1 -> ee11;
                case v6_2 -> ee12;
                default -> unknown;
            };
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }
}
