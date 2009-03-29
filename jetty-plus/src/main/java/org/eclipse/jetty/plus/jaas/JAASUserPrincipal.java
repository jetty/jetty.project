// ========================================================================
// Copyright (c) 2002-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jaas;

import java.security.Principal;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;



/* ---------------------------------------------------- */
/** JAASUserPrincipal
 * <p>Implements the JAAS version of the 
 *  org.eclipse.http.UserPrincipal interface.
 *
 * @version $Id: JAASUserPrincipal.java 4780 2009-03-17 15:36:08Z jesse $
 * 
 */
public class JAASUserPrincipal implements Principal 
{
    private final String _name;
    private final Subject _subject;
    private final LoginContext _loginContext;

    /* ------------------------------------------------ */

    public JAASUserPrincipal(String name, Subject subject, LoginContext loginContext)
    {
        this._name = name;
        this._subject = subject;
        this._loginContext = loginContext;
    }

    /* ------------------------------------------------ */
    /** Get the name identifying the user
     */
    public String getName ()
    {
        return _name;
    }
    
    
    /* ------------------------------------------------ */
    /** Provide access to the Subject
     * @return subject
     */
    public Subject getSubject ()
    {
        return this._subject;
    }
    
    LoginContext getLoginContext ()
    {
        return this._loginContext;
    }
    
    public String toString()
    {
        return getName();
    }
    
}
