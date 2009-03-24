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

package org.eclipse.jetty.annotations;



public interface ClassNameResolver
{
    /**
     * Based on the execution context, should the class represented
     * by "name" be excluded from consideration?
     * @param name
     * @return
     */
    public boolean isExcluded (String name);
    
    
    /**
     * Based on the execution context, if a duplicate class 
     * represented by "name" is detected, should the existing
     * one be overridden or not?
     * @param name
     * @return
     */
    public boolean shouldOverride (String name);
}
