//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security;

import java.util.List;
import java.util.Set;

/**
 * @version $Rev: 4466 $ $Date: 2009-02-10 23:42:54 +0100 (Tue, 10 Feb 2009) $
 */
public interface ConstraintAware
{
    List<ConstraintMapping> getConstraintMappings();
    Set<String> getRoles();
    
    /* ------------------------------------------------------------ */
    /** Set Constraint Mappings and roles.
     * Can only be called during initialization.
     * @param constraintMappings
     * @param roles
     */
    void setConstraintMappings(List<ConstraintMapping> constraintMappings, Set<String> roles);
    
    /* ------------------------------------------------------------ */
    /** Add a Constraint Mapping.
     * May be called for running webapplication as an annotated servlet is instantiated.
     * @param mapping
     */
    void addConstraintMapping(ConstraintMapping mapping);
    
    
    /* ------------------------------------------------------------ */
    /** Add a Role definition.
     * May be called on running webapplication as an annotated servlet is instantiated.
     * @param role
     */
    void addRole(String role);
}
