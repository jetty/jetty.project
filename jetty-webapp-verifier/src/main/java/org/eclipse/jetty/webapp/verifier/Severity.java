// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier;

public enum Severity
{
    WARNING, ERROR;

    public static Severity parse(String value)
    {
        if (value == null)
        {
            return null;
        }

        for (Severity sev : Severity.values())
        {
            if (sev.name().equalsIgnoreCase(value))
            {
                return sev;
            }
        }

        return null;
    }
}
