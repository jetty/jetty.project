package org.eclipse.jetty.util.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target( { ElementType.TYPE, ElementType.METHOD, ElementType.FIELD } )
public @interface ManagedObject
{
    /**
     * Description of the Managed Object
     * 
     * @return
     */
    String value() default "Not Specified";
    
}
