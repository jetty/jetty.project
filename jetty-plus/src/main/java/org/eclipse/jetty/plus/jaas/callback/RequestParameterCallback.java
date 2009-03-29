// ========================================================================
// Copyright (c) 2000-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jaas.callback;

import java.util.List;

import javax.security.auth.callback.Callback;


/**
 * 
 * RequestParameterCallback
 * 
 * Allows a JAAS callback handler to access any parameter from the j_security_check FORM.
 * This means that a LoginModule can access form fields other than the j_username and j_password
 * fields, and use it, for example, to authenticate a user.
 *
 * 
 * @version $Revision: 4780 $ $Date: 2009-03-17 16:36:08 +0100 (Tue, 17 Mar 2009) $
 *
 */
public class RequestParameterCallback implements Callback
{
    private String _paramName;
    private List _paramValues;
    
    public void setParameterName (String name)
    {
        _paramName = name;
    }
    public String getParameterName ()
    {
        return _paramName;
    }
    
    public void setParameterValues (List values)
    {
        _paramValues = values;
    }
    
    public List getParameterValues ()
    {
        return _paramValues;
    }
}
