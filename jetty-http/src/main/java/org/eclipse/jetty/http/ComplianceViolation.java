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

package org.eclipse.jetty.http;

import java.util.Set;

/**
 * A Compliance Violation represents a requirement of an RFC, specification or Jetty implementation
 * that may be allowed to be violated if it is included in a {@link ComplianceViolation.Mode}.
 * For example, supporting HTTP/0.9 is no longer a requirement of the current HTTP RFC, so by including
 * the {@link HttpCompliance.Violation#HTTP_0_9} in the {@link HttpCompliance} {@link ComplianceViolation.Mode}
 * is interpreted as allowing HTTP/0.9 to be supported.
 */
public interface ComplianceViolation
{
    /**
     * @return The name of the violation.
     */
    String getName();

    /**
     * @return A URL to the specification that provides more information regarding the requirement that may be violated.
     */
    String getURL();

    /**
     * @return A short description of the violation.
     */
    String getDescription();

    /**
     * @param mode A {@link ComplianceViolation.Mode} to test against
     * @return True iff this violations is allowed by the mode.
     */
    default boolean isAllowedBy(Mode mode)
    {
        return mode.allows(this);
    }

    /**
     * A Mode is a set of {@link ComplianceViolation}s that are allowed.
     */
    interface Mode
    {
        /**
         * @return The name of the compliance violation mode.
         */
        String getName();

        /**
         * @param violation The {@link ComplianceViolation} to test
         * @return true iff the violation is allowed by this mode.
         */
        boolean allows(ComplianceViolation violation);

        /**
         * @return The immutable set of all known violations for this mode.
         */
        Set<? extends ComplianceViolation> getKnown();

        /**
         * @return The immutable set of violations allowed by this mode.
         */
        Set<? extends ComplianceViolation> getAllowed();
    }

    /**
     * A listener that can be notified of violations.
     */
    interface Listener
    {
        default void onComplianceViolation(Mode mode, ComplianceViolation violation, String details)
        {
        }
    }
}
