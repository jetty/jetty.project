//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi.callback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import javax.security.auth.Subject;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.Test;

public class CredentialValidationCallbackTest
{

    @Test
    public void testGetCredential()
    {
        // given
        String username = "Raju";
        Subject subject = new Subject();
        Credential credential = new Password("password");

        // when
        CredentialValidationCallback credentialValidationCallback = new CredentialValidationCallback(subject, username, credential);

        // then
        assertEquals("Credentials must be equal", credential, credentialValidationCallback.getCredential() );
    }

    @Test
    public void testClearCredential()
    {
        // given
        String username = "Raju";
        Subject subject = new Subject();
        Credential credential = new Password("password");

        // when
        CredentialValidationCallback credentialValidationCallback = new CredentialValidationCallback(subject, username, credential);

        // then
        credentialValidationCallback.clearCredential();
        assertNull("Credentials must be null, as we cleared the credentials", credentialValidationCallback.getCredential() );
    }

    @Test
    public void testGetResult()
    {
        // given
        String username = "Raju";
        Subject subject = new Subject();
        Credential credential = new Password("password");

        // when
        CredentialValidationCallback credentialValidationCallback = new CredentialValidationCallback(subject, username, credential);

        // then
        credentialValidationCallback.setResult(true);
        assertTrue("credentialValidationCalback must return true for valid user", credentialValidationCallback.getResult() );
    }

    @Test
    public void testGetSubject()
    {
        // given
        String username = "Raju";
        Subject subject = new Subject();
        Credential credential = new Password("password");

        // when
        CredentialValidationCallback credentialValidationCallback = new CredentialValidationCallback(subject, username, credential);

        // then
        assertEquals("Subject must be equal", subject, credentialValidationCallback.getSubject() );
    }

    @Test
    public void testGetUsername()
    {
        // given
        String username = "Raju";
        Subject subject = new Subject();
        Credential credential = new Password("password");

        // when
        CredentialValidationCallback credentialValidationCallback = new CredentialValidationCallback(subject, username, credential);

        // then
        assertEquals("UserName must be equal", username, credentialValidationCallback.getUsername() );
    }
}