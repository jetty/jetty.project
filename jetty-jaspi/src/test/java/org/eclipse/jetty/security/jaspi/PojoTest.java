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

package org.eclipse.jetty.security.jaspi;

import com.openpojo.reflection.impl.PojoClassFactory;
import com.openpojo.validation.Validator;
import com.openpojo.validation.ValidatorBuilder;
import com.openpojo.validation.test.impl.GetterTester;
import com.openpojo.validation.test.impl.SetterTester;
import org.eclipse.jetty.security.jaspi.callback.CredentialValidationCallback;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/*
 * This class tests all the getters and setters for a given list of classes.
 */
public class PojoTest
{
    @Test
    public void testOpenPojo()
    {
        Validator validator = ValidatorBuilder.create().with(new SetterTester()).with(new GetterTester()).build();

        List<Class> classes = Arrays.asList(JaspiAuthenticator.class, JaspiAuthenticatorFactory.class, JaspiMessageInfo.class, ServletCallbackHandler.class,
                CredentialValidationCallback.class, SimpleAuthConfig.class);

        for (Class clazz : classes)
        {
            validator.validate(PojoClassFactory.getPojoClass(clazz));
        }
    }
}
