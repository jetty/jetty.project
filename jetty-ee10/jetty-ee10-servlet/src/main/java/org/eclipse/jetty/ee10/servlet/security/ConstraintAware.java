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

package org.eclipse.jetty.ee10.servlet.security;

import java.util.List;
import java.util.Set;

public interface ConstraintAware
{
    List<ConstraintMapping> getConstraintMappings();

    Set<String> getRoles();

    /**
     * Set Constraint Mappings and roles.
     * Can only be called during initialization.
     *
     * @param constraintMappings the mappings
     * @param roles the roles
     */
    void setConstraintMappings(List<ConstraintMapping> constraintMappings, Set<String> roles);

    /**
     * Add a Constraint Mapping.
     * May be called for running webapplication as an annotated servlet is instantiated.
     *
     * @param mapping the mapping
     */
    void addConstraintMapping(ConstraintMapping mapping);

    /**
     * Add a Role definition.
     * May be called on running webapplication as an annotated servlet is instantiated.
     *
     * @param role the role
     */
    void addRole(String role);

    /**
     * See Servlet Spec 31, sec 13.8.4, pg 145
     * When true, requests with http methods not explicitly covered either by inclusion or omissions
     * in constraints, will have access denied.
     *
     * @param deny true for denied method access
     */
    void setDenyUncoveredHttpMethods(boolean deny);

    boolean isDenyUncoveredHttpMethods();

    /**
     * See Servlet Spec 31, sec 13.8.4, pg 145
     * Container must check if there are urls with uncovered http methods
     *
     * @return true if urls with uncovered http methods
     */
    boolean checkPathsWithUncoveredHttpMethods();
}
