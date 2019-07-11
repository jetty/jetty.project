//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.util.Set;

public interface ComplianceViolation
{
    String getName();

    String getURL();

    String getDescription();

    default boolean isAllowedBy(Mode mode)
    {
        return mode.allows(this);
    }

    interface Mode
    {
        String getName();

        boolean allows(ComplianceViolation violation);

        Set<? extends ComplianceViolation> getKnown();

        Set<? extends ComplianceViolation> getAllowed();
    }

    interface Listener
    {
        default void onComplianceViolation(Mode mode, ComplianceViolation violation, String details)
        {
        }
    }
}
