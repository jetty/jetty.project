//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jndi;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.naming.Binding;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingException;
import org.eclipse.jetty.jndi.InitialContextFactory;

/**
 * Unit tests for class {@link NamingEntryUtil}.
 *
 * @see NamingEntryUtil
 *
 */
public class NamingEntryUtilTest{

  @Test(expected = NamingException.class)
  public void testBindToENCWithEmptyStringAndBindToENCThrowsNamingException() throws NamingException {
      NamingEntryUtil.bindToENC(new Object(), "", "");
  }

  @Test(expected = NamingException.class)
  public void testBindToENCWithNullAndNullThrowsNamingException() throws NamingException {
      NamingEntryUtil.bindToENC( null, null, "@=<9");
  }

}