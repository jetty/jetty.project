// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNameValue;

public abstract class AbstractSecurityAnnotationHandler implements AnnotationHandler
{
    
    
    public boolean isHttpMethod (String methodName)
    {
        if ("doGet".equals(methodName) ||
                "doPost".equals(methodName) ||
                "doDelete".equals(methodName) ||
                "doHead".equals(methodName) ||
                "doOptions".equals(methodName) ||
                "doPut".equals(methodName) ||
                "doTrace".equals(methodName))
        {
            return true;
        }

        return false;
    }

}
