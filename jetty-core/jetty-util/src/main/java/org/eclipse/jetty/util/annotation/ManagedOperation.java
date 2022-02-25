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
 * The <code>&#064;ManagedOperation</code> annotation is used to indicate that a given method
 * should be considered a JMX operation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target({ElementType.METHOD})
public @interface ManagedOperation
{
    /**
     * Description of the Managed Object
     *
     * @return value
     */
    String value() default "Not Specified";

    /**
     * The impact of an operation.
     *
     * NOTE: Valid values are UNKNOWN, ACTION, INFO, ACTION_INFO
     *
     * NOTE: applies to METHOD
     *
     * @return String representing the impact of the operation
     */
    String impact() default "UNKNOWN";

    /**
     * Does the managed field exist on a proxy object?
     *
     * @return true if a proxy object is involved
     */
    boolean proxied() default false;
}
