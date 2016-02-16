//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
 * The <code>&#064;ManagedObject</code> annotation is used on a class at the top level to 
 * indicate that it should be exposed as an mbean. It has only one attribute 
 * to it which is used as the description of the MBean. Should multiple 
 * <code>&#064;ManagedObject</code> annotations be found in the chain of influence then the 
 * first description is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target( { ElementType.TYPE } )
public @interface ManagedObject
{
    /**
     * Description of the Managed Object
     * @return value
     */
    String value() default "Not Specified";
  
}
