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

package org.eclipse.jetty.util.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The <code>&#064;ManagedObject</code> annotation is used on a class at the top level to
 * indicate that it should be exposed as an mbean. It has only one attribute
 * to it which is used as the description of the MBean. Should multiple
 * <code>&#064;ManagedObject</code> annotations be found in the chain of influence then the
 * first description is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target({ElementType.TYPE})
public @interface ManagedObject
{
    /**
     * Description of the Managed Object
     *
     * @return value
     */
    String value() default "Not Specified";
}
