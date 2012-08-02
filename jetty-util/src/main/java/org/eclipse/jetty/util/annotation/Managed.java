package org.eclipse.jetty.util.annotation;
//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Deprecated
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target( { ElementType.TYPE, ElementType.METHOD, ElementType.FIELD } )
public @interface Managed
{
    /**
     * Description of the Managed Object
     * 
     * @return
     */
    String value() default "Not Specified";
    
    /**
     * Is the managed field read-only?
     * 
     * NOTE: applies to FIELD
     * 
     * @return true if readonly
     */
    boolean readonly() default false;
    
    /**
     * Is the managed field itself a Managed Object?
     * 
     * NOTE: applies to FIELD
     * 
     * @return true if the target is a Managed Object
     */
    boolean managed() default false;
    
    /**
     * Does the managed field or method exist on a proxy object?
     * 
     * NOTE: applies to FIELD and METHOD
     * 
     * @return true if a proxy object is involved
     */
    boolean proxied() default false;
    
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
     * If is a field references a getter that doesn't conform to standards for discovery
     * it can be set here.
     * 
     * NOTE: applies to FIELD
     * 
     * @return the full name of the getter in question
     */
    String getter() default "";
    
    /**
     * If is a field references a setter that doesn't conform to standards for discovery
     * it can be set here.
     * 
     * NOTE: applies to FIELD
     * 
     * @return the full name of the setter in question
     */
    String setter() default "";
    
    /**
     * Treat method as an attribute and not an operation
     * 
     * NOTE: applies to METHOD
     * 
     * @return true of the method should be treating as an attribute
     */
    boolean attribute() default false;
}
