// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util.log;

/** Logging Facade
 * A simple logging facade that is intended simply to capture the style 
 * of logging as used by Jetty.
 *
 */
public interface Logger
{
    public boolean isDebugEnabled();

    /** Mutator used to turn debug on programatically.
     * Implementations operation in which case an appropriate
     * warning message shall be generated.
     */
    public void setDebugEnabled(boolean enabled);

    public void info(String msg);
    public void info(String msg,Object arg0, Object arg1);
    public void debug(String msg);
    public void debug(String msg,Throwable th);
    public void debug(String msg,Object arg0, Object arg1);
    public void warn(String msg);
    public void warn(String msg,Object arg0, Object arg1);
    public void warn(String msg, Throwable th);
    public Logger getLogger(String name);
    
    public String getName();
}
