//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is used to describe variables in method
 * signatures so that when rendered into tools like JConsole
 * it is clear what the parameters are. For example:
 *
 * public void doodle(@Name(value="doodle", description="A description of the argument") String doodle)
 */
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target({ElementType.PARAMETER})
public @interface Name
{
    /**
     * the name of the parameter
     *
     * @return the value
     */
    String value();

    /**
     * the description of the parameter
     *
     * @return the description
     */
    String description() default "";
}
