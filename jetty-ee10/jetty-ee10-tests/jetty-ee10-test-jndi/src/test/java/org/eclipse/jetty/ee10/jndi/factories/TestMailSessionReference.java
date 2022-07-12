//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Properties;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.LinkRef;
import javax.naming.Name;
import javax.naming.NameParser;

import org.eclipse.jetty.ee10.jndi.factories.MailSessionReference;
import org.eclipse.jetty.jndi.NamingUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class TestMailSessionReference
{
    @Test
    public void testMailSessionReference() throws Exception
    {
        InitialContext icontext = new InitialContext();
        MailSessionReference sref = new MailSessionReference();
        sref.setUser("janb");
        sref.setPassword("OBF:1xmk1w261z0f1w1c1xmq");
        Properties props = new Properties();
        props.put("mail.smtp.host", "xxx");
        props.put("mail.debug", "true");
        sref.setProperties(props);
        NamingUtil.bind(icontext, "mail/Session", sref);
        Object x = icontext.lookup("mail/Session");
        assertNotNull(x);
        assertTrue(x instanceof jakarta.mail.Session);
        jakarta.mail.Session session = (jakarta.mail.Session)x;
        Properties sessionProps = session.getProperties();
        assertEquals(props, sessionProps);
        assertTrue(session.getDebug());

        Context foo = icontext.createSubcontext("foo");
        NameParser parser = icontext.getNameParser("");
        Name objectNameInNamespace = parser.parse(icontext.getNameInNamespace());
        objectNameInNamespace.addAll(parser.parse("mail/Session"));

        NamingUtil.bind(foo, "mail/Session", new LinkRef(objectNameInNamespace.toString()));

        Object o = foo.lookup("mail/Session");
        assertNotNull(o);
        jakarta.mail.Session fooSession = (jakarta.mail.Session)o;
        assertEquals(props, fooSession.getProperties());
        assertTrue(fooSession.getDebug());

        icontext.destroySubcontext("mail");
        icontext.destroySubcontext("foo");
    }
}
