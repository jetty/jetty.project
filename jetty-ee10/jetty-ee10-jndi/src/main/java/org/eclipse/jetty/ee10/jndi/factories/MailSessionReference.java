//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.jndi.factories;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.eclipse.jetty.util.security.Password;

/**
 * This is a subclass of jakarta.mail.Reference and an ObjectFactory for jakarta.mail.Session objects.
 * <p>
 * The subclassing of Reference allows all of the setup for a jakarta.mail.Session
 * to be captured without necessitating first instantiating a Session object. The
 * reference is bound into JNDI and it is only when the reference is looked up that
 * this object factory will create an instance of jakarta.mail.Session using the
 * information captured in the Reference.
 */
public class MailSessionReference extends org.eclipse.jetty.ee.jndi.factories.MailSessionReference
{
    public MailSessionReference()
    {
        super();
    }
}
