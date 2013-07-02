//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.utils;

import java.lang.reflect.Type;
import java.util.Set;

/**
 * Collection of common {@link Type} utility methods used during Deployment of the Endpoint
 */
public class DeploymentTypeUtils
{
    public static Class<?> getPrimitiveClass(Class<?> primitiveType)
    {
        return Primitives.getPrimitiveClass(primitiveType);
    }

    public static Set<Class<?>> getPrimitiveClasses()
    {
        return Primitives.getPrimitiveClasses();
    }

    public static Set<Class<?>> getPrimitives()
    {
        return Primitives.getPrimitives();
    }

    public static boolean isAssignableClass(Class<?> type, Class<?> targetClass)
    {
        if ((type == null) || (targetClass == null))
        {
            return false;
        }

        // Casting from primitive
        if (type.isPrimitive())
        {
            // Primitive to Class (autoboxing)
            if (!targetClass.isPrimitive())
            {
                // going from primitive to class, make sure it matches
                Class<?> primitive = Primitives.getPrimitiveType(targetClass);
                return primitive.equals(type);
            }

            // Primitive to Primitive

            /**
             * After reading the javadoc for the various primitive class implementations. It is determined that the appropriate allowable casting from one
             * primitive to another can be discovered via the existence (or not) of various .*Value() methods.
             * 
             * The table:
             * 
             * <pre>
             *        | :From:
             * :To:   | Bool | Byte | Char | Double | Float | Int | Long | Short
             * ------------------------------------------------------------------
             * Bool   |  Y   |      |      |        |       |     |      |     
             * Byte   |      |  Y   |      |  Y     |  Y    |  Y  |  Y   | Y
             * Char   |      |      |  Y   |        |       |     |      |
             * Double |      |  Y   |      |  Y     |  Y    |  Y  |  Y   | Y
             * Float  |      |  Y   |      |  Y     |  Y    |  Y  |  Y   | Y
             * Int    |      |  Y   |      |  Y     |  Y    |  Y  |  Y   | Y
             * Long   |      |  Y   |      |  Y     |  Y    |  Y  |  Y   | Y
             * Short  |      |  Y   |      |  Y     |  Y    |  Y  |  Y   | Y
             * </pre>
             */

            if (Byte.TYPE.equals(type) || Double.TYPE.equals(type) || Float.TYPE.equals(type) || Integer.TYPE.equals(type) || Long.TYPE.equals(type)
                    || Short.TYPE.equals(type))
            {
                return Byte.TYPE.equals(targetClass) || Double.TYPE.equals(targetClass) || Float.TYPE.equals(targetClass) || Integer.TYPE.equals(targetClass)
                        || Long.TYPE.equals(targetClass) || Short.TYPE.equals(targetClass);
            }

            // All others are not valid
            return false;
        }
        else
        {
            if (targetClass.isPrimitive())
            {
                // Class to Primitive (autoboxing)
                Class<?> targetPrimitive = Primitives.getPrimitiveType(type);
                return targetClass.equals(targetPrimitive);
            }
        }

        // Basic class check
        if (type.equals(targetClass))
        {
            return true;
        }

        // Basic form
        return targetClass.isAssignableFrom(type);
    }
}
