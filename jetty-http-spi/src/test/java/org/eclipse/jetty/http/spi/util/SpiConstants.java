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

package org.eclipse.jetty.http.spi.util;

/**
 * This class holds the constant required for test cases.
 */
public class SpiConstants
{

    public static final int[] poolInfo =
        {Pool.MAXIMUM_POOL_SIZE.getValue(), Pool.KEEP_ALIVE_TIME.getValue(), Pool.DEFAULT_WORK_QUEUE_SIZE.getValue()};

    public static final String LOCAL_HOST = "localhost";

    public static final int DEFAULT_PORT = 0;

    public static final int ONE = 1;

    public static final int MINUS_ONE = -1;

    public static final int TWO = 2;

    public static final int ZERO = 0;

    public static final int BACK_LOG = 10;

    public static final int DELAY = 1;

    public static final String PROTOCOL = "HTTP";

    public static final String QUERY_STRING = "key1=value1,key2=value2";

    public static final String REQUEST_URI = "/cp/helloworld";

    public static final String ACCEPT_LANGUAGE = "Accept-Language";

    public static final String EN_US = "en-US";

    public static final String ACCEPT = "Accept";

    public static final String TEXT_PLAIN = "text/plain";

    public static final String ACCEPT_CHARSET = "Accept-Charset";

    public static final String UTF_8 = "utf-8";

    public static final String REQUEST_METHOD = "POST";

    public static final String USER_NAME = "USER NAME";

    public static final String PASSWORD = "PASSWORD";

    public static final String VALID_USER = "user1";

    public static final String VALID_PASSWORD = "pswd";

    public static final Integer FAILURE_STATUS = 500;

    public static final Integer RETRY_STATUS = 300;

    public static final Integer TWO_HUNDRED = 200;

    public static final Integer HUNDRED = 100;

    public static final Integer THOUSAND = 1000;
}
