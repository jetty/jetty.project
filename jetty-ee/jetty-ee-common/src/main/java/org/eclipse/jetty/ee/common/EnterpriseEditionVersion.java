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
     * EE10 is in use.
     */
    EE10(10),
    /**
     * EE11 is in use.
     */
    EE11(11),
    /**
     * EE12 is in use.
     */
    EE12(12);

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
                case V6_0 -> EE10;
                case V6_1 -> EE11;
                case V6_2 -> EE12;
                default -> throw new RuntimeException("Unable to Initialize " + EnterpriseEditionVersion.class.getName());
            };
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }
}
