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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.powermock.api.mockito.PowerMockito.mock;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.junit.Before;
import org.junit.Test;

public class JaspiMessageInfoTest
{

    private static final String AUTH_TYPE1 = "AUTH_TYPE1";

    private static final String AUTH_TYPE1_VALUE = "One Time Password";

    private static final String AUTH_METHOD = "public_key";

    private JaspiMessageInfo jaspiMessageInfo;

    private ServletRequest request;

    private ServletResponse response;

    private Map miMap;;

    private static final Integer ZERO = 0;

    private static final Integer ONE = 1;

    private static final Integer THREE = 3;

    @Before
    public void setUp() throws Exception
    {
        request = mock(ServletRequest.class);
        response = mock(ServletResponse.class);
        Boolean isAuthMandatory = true;
        initializeJaspiMsgInfo(isAuthMandatory);
    }

    @Test
    public void testBasicOperations()
    {
        // given
        ServletRequest mockRequest = mock(ServletRequest.class);
        jaspiMessageInfo.setRequestMessage(mockRequest);
        ServletResponse mockResponse = mock(ServletResponse.class);
        jaspiMessageInfo.setResponseMessage(mockResponse);

        // then
        assertEquals("Request instances should be equal", mockRequest, (ServletRequest)jaspiMessageInfo.getRequestMessage() );
        assertEquals("Response instances should be equal", mockResponse, (ServletResponse)jaspiMessageInfo.getResponseMessage() );
        assertTrue("This method call should return true as authentication is mandatory", jaspiMessageInfo.isAuthMandatory() );
    }

    @Test
    public void testMIMapElements()
    {
        // testing default values
        assertNotNull("Map contains one element(default mandatory attribute), so entryset shouldn't be null", miMap.entrySet() );
        assertNotNull("Map contains one element(default mandatory attribute), so keyset shouldn't be null", miMap.keySet() );
        assertNotNull("Map contains one element(default mandatory attribute), so values shouldn't be null", miMap.values() );
    }

    @Test
    public void testMIMapCrudOperations()
    {
        // given
        Map elements = new HashMap();
        elements.put(AUTH_TYPE1, AUTH_TYPE1_VALUE);
        elements.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // when
        miMap.putAll(null);

        // then
        assertEquals("Map size must not change with above operations. Default mapsize is 1 as mandatory key is true.", ONE, (Integer)miMap.size() );

        // when
        miMap.putAll(elements);

        // then
        assertEquals("Auth method should be equal to public_key", AUTH_METHOD, jaspiMessageInfo.getAuthMethod() );
        assertEquals("Auth type key value should be equal to One Time Password", AUTH_TYPE1_VALUE, miMap.get(AUTH_TYPE1) );
        assertEquals("Auth method key value should be equal to public_key", AUTH_METHOD, miMap.get(JaspiMessageInfo.AUTH_METHOD_KEY) );
        assertTrue("Mandatory key value should be true", new Boolean(miMap.get(JaspiMessageInfo.MANDATORY_KEY) + "") );
        assertTrue("One time password value should be in map", miMap.containsValue(AUTH_TYPE1_VALUE) );
        assertTrue("Public_key value should be in map", miMap.containsValue(AUTH_METHOD) );
        assertTrue("True value should be in map as authentication is mandatory", miMap.containsValue("true") );
        assertEquals("One time password value should match with key AUTH_TYPE1", AUTH_TYPE1_VALUE, miMap.remove(AUTH_TYPE1) );
        assertEquals("Public_key value should match with key AUTH_METHOD_KEY", AUTH_METHOD, miMap.remove(JaspiMessageInfo.AUTH_METHOD_KEY) );
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPutMandatoryKeyException()
    {
        // when
        miMap.put(JaspiMessageInfo.MANDATORY_KEY, "test");

        // then
        fail("An IllegalArgumentException must have occurred by now as the value of mandatory key must be in true/false");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRemoveMandatoryKeyException()
    {
        // when
        miMap.remove(JaspiMessageInfo.MANDATORY_KEY);

        // then
        fail("An IllegalArgumentException must have occurred by now as we cannot remove mandatory key");
    }

    @Test
    public void testRemove()
    {
        // given
        assertNull("AUTH_TYPE1 key value must be null as we cleared the map", miMap.remove(AUTH_TYPE1) );
        assertNull("This should return null as we cleared map(delegate cleared automatically)", miMap.get(AUTH_TYPE1) );
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // then
        assertEquals("The value must be equal to auth_method", AUTH_METHOD, miMap.remove(JaspiMessageInfo.AUTH_METHOD_KEY) );
    }

    @Test
    public void testMIMapSize()
    {
        // given
        assertEquals("Map initial size should be 1 as isAuthMandatory value true", ONE, (Integer)miMap.size() );
        initializeJaspiMsgInfo(false);

        // when
        miMap.clear();

        // then
        assertEquals("Map size should be 0 as we cleared map", ZERO, (Integer)miMap.size() );
    }

    @Test
    public void testMandatoryAttributeMandatoryKey()
    {
        // when
        initializeJaspiMsgInfo(false);

        // then
        assertFalse("Mandatory attribute shouldn't be in map", miMap.containsKey(JaspiMessageInfo.MANDATORY_KEY) );
        assertEquals("Map size must be zero as mandatory attribute false", (Integer)ZERO, (Integer)miMap.size() );

        // when
        initializeJaspiMsgInfo(true);

        // then
        assertTrue("Mandatory attribute should be in map", miMap.containsKey(JaspiMessageInfo.MANDATORY_KEY) );
        assertTrue("Mandatory attribute value must be true", miMap.containsValue("true") );
        assertEquals("Map size must be one as mandatory attribute true", (Integer)ONE, (Integer)miMap.size() );
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMandatoryAttributeMandatoryKeyExceptionCheck()
    {
        // setup
        initializeJaspiMsgInfo(true);
        assertTrue("Mandatory attribute should be in map", miMap.containsKey(JaspiMessageInfo.MANDATORY_KEY) );
        assertTrue("Mandatory attribute value must be true", miMap.containsValue("true") );

        // when
        miMap.put(JaspiMessageInfo.MANDATORY_KEY, "true");

        // then
        fail("An IllegalArgumentException must have occurred by now as mandatory elements are not mutable");
    }

    @Test
    public void testMandatoryAttributeAuthMethodKeyForNullDelegate()
    {
        // setup
        initializeJaspiMsgInfo(false);
        assertEquals("Map size must be zero as mandatory attribute false", (Integer)ZERO, (Integer)miMap.size() );

        // when
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // then
        // as delegate is null map takes this mandatory attribute value as 1
        // delegate is null, auth method will store in mandatory attribute only.
        assertTrue("Auth method key must be in map", miMap.containsKey(JaspiMessageInfo.AUTH_METHOD_KEY) );
        assertTrue("Map must contain auth method as value", miMap.containsValue(AUTH_METHOD) );
        assertEquals("Map size must be one as mandatory attribute true", (Integer)ONE, (Integer)miMap.size() );

        // when
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_TYPE1_VALUE);

        // then
        assertTrue("Auth method key must be in map", miMap.containsKey(JaspiMessageInfo.AUTH_METHOD_KEY) );
        assertTrue("Map must contain auth type1 value as value", miMap.containsValue(AUTH_TYPE1_VALUE) );
        assertEquals("Map size must be one as mandatory attribute true", (Integer)ONE, (Integer)miMap.size() );
    }

    @Test
    public void testMandatoryAttributeForAuthMethodKey()
    {
        // setup
        initializeJaspiMsgInfo(false);
        assertEquals("Map size must be zero as mandatory attribute false", (Integer)ZERO, (Integer)miMap.size() );

        // when
        miMap.put(AUTH_TYPE1, AUTH_TYPE1_VALUE);

        // then
        assertEquals("Map size should be 1 as we added one element", ONE, (Integer)miMap.size() );
        assertTrue("Map must contain auth type1 as key", miMap.containsKey(AUTH_TYPE1) );
        assertTrue("Map must contain auth type1 value as value", miMap.containsValue(AUTH_TYPE1_VALUE) );

        // when
        // delegate is populated with Auth_TYPE1. so below AUTH_METHOD_KEY will store into two places.1. mandatory key 2. delegate
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // then
        // current code calculates the authenication key size as 2 even though it is
        // one key because it is stored in both mandatory key as well as delegate
        assertEquals("Map size should be 3 as we added AUTH_METHOD_KEY ", THREE, (Integer)miMap.size() );
        assertTrue("Map must contain auth method key as key", miMap.containsKey(JaspiMessageInfo.AUTH_METHOD_KEY) );
        assertTrue("Map must contain auth method as value", miMap.containsValue(AUTH_METHOD) );
    }

    @Test
    public void testMIMapIsEmpty()
    {
        // when
        initializeJaspiMsgInfo(false);

        // then
        assertTrue("Map must be empty as we cleared", miMap.isEmpty() );

        // when
        miMap.put(AUTH_TYPE1, AUTH_TYPE1_VALUE);

        // then
        assertFalse("Map must be non empty", miMap.isEmpty() );

        // when
        miMap.remove(AUTH_TYPE1);

        // then
        assertTrue("Map must be empty as we removed elements", miMap.isEmpty() );

        // when
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // then
        assertFalse("Map must be non empty", miMap.isEmpty() );
    }

    @Test
    public void testContainsKey()
    {
        // when
        miMap.clear();

        // then
        assertFalse("Map must not contain any elements as we cleared the map", miMap.containsKey(AUTH_TYPE1 ));
        assertFalse("Map must not contain any elements as we cleared the map", miMap.containsKey(JaspiMessageInfo.AUTH_METHOD_KEY) );

        // when
        miMap.put(AUTH_TYPE1, AUTH_TYPE1_VALUE);

        // then
        assertTrue("Map must contain auth_type", miMap.containsKey(AUTH_TYPE1) );

        // when
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // then
        assertTrue("Map must contain auth method key", miMap.containsKey(JaspiMessageInfo.AUTH_METHOD_KEY) );
        assertFalse("Map must not contain test as key", miMap.containsKey("test") );
    }

    @Test
    public void testContainsValue()
    {
        // when
        initializeJaspiMsgInfo(true);

        // then
        assertTrue("Map must contain true value", miMap.containsValue("true") );
        assertFalse("Map must not contain any other value", miMap.containsValue(AUTH_TYPE1_VALUE) );

        // when
        initializeJaspiMsgInfo(false);

        // then
        assertFalse("Map must not contain true value", miMap.containsValue("true") );

        // when
        miMap.put(JaspiMessageInfo.AUTH_METHOD_KEY, AUTH_METHOD);

        // then
        assertTrue("Map must contain auth method as value", miMap.containsValue(AUTH_METHOD) );
        assertFalse("Map must not contain true value", miMap.containsValue("true") );
    }

    private void initializeJaspiMsgInfo(Boolean mandatory)
    {
        jaspiMessageInfo = new JaspiMessageInfo(request, response, mandatory);
        miMap = jaspiMessageInfo.getMap();
    }
}
