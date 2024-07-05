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

package org.eclipse.jetty.security.siwe.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a SIWE Message, based off the ABNF Message Format from <a href="https://eips.ethereum.org/EIPS/eip-4361">EIP-4361</a>.
 */
public class SignInWithEthereumParser
{
    private static final String SCHEME_PATTERN = "[a-zA-Z][a-zA-Z0-9+\\-.]*";
    private static final String DOMAIN_PATTERN = "(?:[a-zA-Z0-9\\-._~%]+@)?[a-zA-Z0-9\\-._~%]+(?:\\:[0-9]+)?";
    private static final String ADDRESS_PATTERN = "0x[0-9a-fA-F]{40}";
    private static final String STATEMENT_PATTERN = "[^\\n]*";
    private static final String URI_PATTERN = "[^\\n]+";
    private static final String VERSION_PATTERN = "[0-9]+";
    private static final String CHAIN_ID_PATTERN = "[0-9]+";
    private static final String NONCE_PATTERN = "[a-zA-Z0-9]{8}";
    private static final String DATE_TIME_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?";
    private static final String REQUEST_ID_PATTERN = "[^\\n]*";
    private static final String RESOURCE_PATTERN = "- " + URI_PATTERN;
    private static final String RESOURCES_PATTERN = "(?:\n" + RESOURCE_PATTERN + ")*";
    private static final Pattern SIGN_IN_WITH_ETHEREUM_PATTERN = Pattern.compile(
        "^(?:(?<scheme>" + SCHEME_PATTERN + ")://)?(?<domain>" + DOMAIN_PATTERN + ") wants you to sign in with your Ethereum account:\n" +
            "(?<address>" + ADDRESS_PATTERN + ")\n\n" +
            "(?<statement>" + STATEMENT_PATTERN + ")?\n\n" +
            "URI: (?<uri>" + URI_PATTERN + ")\n" +
            "Version: (?<version>" + VERSION_PATTERN + ")\n" +
            "Chain ID: (?<chainId>" + CHAIN_ID_PATTERN + ")\n" +
            "Nonce: (?<nonce>" + NONCE_PATTERN + ")\n" +
            "Issued At: (?<issuedAt>" + DATE_TIME_PATTERN + ")" +
            "(?:\nExpiration Time: (?<expirationTime>" + DATE_TIME_PATTERN + "))?" +
            "(?:\nNot Before: (?<notBefore>" + DATE_TIME_PATTERN + "))?" +
            "(?:\nRequest ID: (?<requestId>" + REQUEST_ID_PATTERN + "))?" +
            "(?:\nResources:(?<resources>" + RESOURCES_PATTERN + "))?$",
        Pattern.DOTALL
    );

    private SignInWithEthereumParser()
    {
    }

    /**
     * Parse a {@link SignInWithEthereumToken} from a {@link String}.
     * @param message the SIWE message to parse.
     * @return the {@link SignInWithEthereumToken} or null if it was not a valid SIWE message.
     */
    public static SignInWithEthereumToken parse(String message)
    {
        Matcher matcher = SIGN_IN_WITH_ETHEREUM_PATTERN.matcher(message);
        if (!matcher.matches())
            return null;

        return new SignInWithEthereumToken(matcher.group("scheme"), matcher.group("domain"),
            matcher.group("address"), matcher.group("statement"), matcher.group("uri"),
            matcher.group("version"), matcher.group("chainId"), matcher.group("nonce"),
            matcher.group("issuedAt"), matcher.group("expirationTime"), matcher.group("notBefore"),
            matcher.group("requestId"), matcher.group("resources"));
    }
}
