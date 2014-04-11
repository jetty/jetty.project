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

package org.eclipse.jetty.util.log;


/* ------------------------------------------------------------ */
/** Abstract Logger.
 * Manages the atomic registration of the logger by name.
 */
public abstract class AbstractLogger implements Logger
{
    public final Logger getLogger(String name)
    {
        if (isBlank(name))
            return this;

        final String basename = getName();
        final String fullname = (isBlank(basename) || Log.getRootLogger()==this)?name:(basename + "." + name);
        
        Logger logger = Log.getLoggers().get(fullname);
        if (logger == null)
        {
            Logger newlog = newLogger(fullname);
            
            logger = Log.getMutableLoggers().putIfAbsent(fullname,newlog);
            if (logger == null)
                logger=newlog;
        }

        return logger;
    }
    

    protected abstract Logger newLogger(String fullname);

    /**
     * A more robust form of name blank test. Will return true for null names, and names that have only whitespace
     *
     * @param name
     *            the name to test
     * @return true for null or blank name, false if any non-whitespace character is found.
     */
    private static boolean isBlank(String name)
    {
        if (name == null)
        {
            return true;
        }
        int size = name.length();
        char c;
        for (int i = 0; i < size; i++)
        {
            c = name.charAt(i);
            if (!Character.isWhitespace(c))
            {
                return false;
            }
        }
        return true;
    }
}
