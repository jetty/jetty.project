//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security;

/**
 * @version $Rev: 4466 $ $Date: 2009-02-10 23:42:54 +0100 (Tue, 10 Feb 2009) $
 */
public enum UserDataConstraint
{
    None, Integral, Confidential;

    public static UserDataConstraint get(int dataConstraint)
    {
        if (dataConstraint < -1 || dataConstraint > 2)
            throw new IllegalArgumentException("Expected -1, 0, 1, or 2, not: " + dataConstraint);
        if (dataConstraint == -1)
            return None;
        return values()[dataConstraint];
    }

    public UserDataConstraint combine(UserDataConstraint other)
    {
        if (this.compareTo(other) < 0)
            return this;
        return other;
    }
}
