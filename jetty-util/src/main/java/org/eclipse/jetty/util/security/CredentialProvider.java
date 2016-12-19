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

package org.eclipse.jetty.util.security;

/**
 * Provider of credentials, it converts a String into a credential if it starts with a given prefix
 *
 */
public interface CredentialProvider
{
    /**
     * Get a credential from a String
     * 
     * @param credential
     *            String representation of the credential
     * @return A Credential or Password instance.
     */
    Credential getCredential(String credential);

    /**
     * Get the prefix of the credential strings convertible into credentials
     * 
     * @return prefix of the credential strings convertible into credentials
     */
    String getPrefix();
}
