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

package org.eclipse.jetty.http;

import java.util.function.Function;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ComplianceUtils
{
    private static final Logger LOG = LoggerFactory.getLogger(ComplianceUtils.class);

    /**
     * Check that the {@link ComplianceViolation} is allowed per the provided {@link ComplianceViolation.Mode}.
     *
     * <p>
     * If a {@link ComplianceViolation.Listener} is provided, notify it of the check.
     * </p>
     *
     * <p>
     * It is assumed that if you are calling this method, a violation has been detected.
     * The purpose of this method is to check if it is allowed, and also notify that the
     * violation was detected (along with the details and allowed state).
     * </p>
     *
     * @param compliance the compliance mode
     * @param violation the violation
     * @param complianceListener the listener to notify (can be null)
     * @return true if violation is allowed per the configured compliance.
     */
    public static boolean allows(ComplianceViolation.Mode compliance,
                                 ComplianceViolation violation,
                                 ComplianceViolation.Listener complianceListener)
    {
        return allows(compliance, violation, violation.getDescription(), complianceListener);
    }

    /**
     * Check that the {@link ComplianceViolation} is allowed per the provided {@link ComplianceViolation.Mode}.
     *
     * <p>
     * If a {@link ComplianceViolation.Listener} is provided, notify it of the check.
     * </p>
     *
     * <p>
     * It is assumed that if you are calling this method, a violation has been detected.
     * The purpose of this method is to check if it is allowed, and also notify that the
     * violation was detected (along with the details and allowed state).
     * </p>
     *
     * @param compliance the compliance mode
     * @param violation the violation
     * @param detail the detail of the violation
     * @param complianceListener the listener to notify (can be null)
     * @return true if violation is allowed per the configured compliance.
     */
    public static boolean allows(ComplianceViolation.Mode compliance,
                                 ComplianceViolation violation,
                                 String detail,
                                 ComplianceViolation.Listener complianceListener)
    {
        boolean allowed = compliance.allows(violation);
        notify(complianceListener, new ComplianceViolation.Event(compliance, violation, detail, allowed));
        return allowed;
    }

    public static void notify(ComplianceViolation.Listener complianceListener, ComplianceViolation.Event event)
    {
        if (complianceListener == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to notify null ComplianceViolation.Listener of {}", event);
            return;
        }

        try
        {
            complianceListener.onComplianceViolation(event);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failure while notifying listener {} of event {}", complianceListener, event, x);
        }
    }

    /**
     * Verify that the {@link HttpURI} has no {@link UriCompliance} violations..
     *
     * @param uriCompliance the configured UriCompliance to apply
     * @param uri the HttpURI to check for violations
     * @param listener the listener to report to
     * @param error the function to produce a Throwable if not allowed
     * @param <T> the type of Throwable
     * @throws T Throwable if not allowed
     */
    public static <T extends Throwable> void verify(UriCompliance uriCompliance, HttpURI uri, ComplianceViolation.Listener listener, Function<String, T> error) throws T
    {
        if (!uri.hasViolations())
            return;

        StringBuilder violations = null;
        for (UriCompliance.Violation violation : uri.getViolations())
        {
            boolean allowed = allows(uriCompliance, violation, violation.getDescription(), listener);

            // Only trigger a failure of the HttpURI for compliance reasons if the compliance doesn't allow for violation detected
            if (!allowed)
            {
                if (violations == null)
                    violations = new StringBuilder();
                else
                    violations.append(", ");
                violations.append(violation.getDescription());
            }
        }
        if (violations != null)
        {
            throw error.apply(violations.toString());
        }
    }

    /**
     * Check the provided Request against configured {@link HttpCompliance}.
     *
     * @param httpCompliance the HttpCompliance to use.
     * @param request the request to check
     * @param listener the notification method for violations.  (Tip: use the Request specific Listener from the {@code HttpChannelState})
     * @throws HttpException.RuntimeException if there is a violation that wasn't allowed
     */
    public static void verify(HttpCompliance httpCompliance, MetaData.Request request, ComplianceViolation.Listener listener)
    {
        boolean seenContentLength = false;
        boolean seenTransferEncoding = false;
        boolean seenHostHeader = false;

        HttpFields fields = request.getHttpFields();
        for (HttpField httpField : fields)
        {
            if (httpField.getHeader() == null)
                continue;

            switch (httpField.getHeader())
            {
                case CONTENT_LENGTH ->
                {
                    if (seenContentLength && !allows(httpCompliance, HttpCompliance.Violation.MULTIPLE_CONTENT_LENGTHS, listener))
                    {
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.MULTIPLE_CONTENT_LENGTHS.getDescription());
                    }
                    String[] lengths = httpField.getValues();
                    if (lengths.length > 1 && !allows(httpCompliance, HttpCompliance.Violation.MULTIPLE_CONTENT_LENGTHS, listener))
                    {
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.MULTIPLE_CONTENT_LENGTHS.getDescription());
                    }
                    if (seenTransferEncoding && !allows(httpCompliance, HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH, listener))
                    {
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH.getDescription());
                    }
                    seenContentLength = true;
                }
                case TRANSFER_ENCODING ->
                {
                    if (seenContentLength && !allows(httpCompliance, HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH, listener))
                    {
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH.getDescription());
                    }
                    seenTransferEncoding = true;
                }
                case HOST ->
                {
                    if (seenHostHeader && !allows(httpCompliance, HttpCompliance.Violation.DUPLICATE_HOST_HEADERS, listener))
                    {
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.DUPLICATE_HOST_HEADERS.getDescription());
                    }
                    String[] hostValues = httpField.getValues();
                    if (hostValues.length > 1 && !allows(httpCompliance, HttpCompliance.Violation.DUPLICATE_HOST_HEADERS, listener))
                    {
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.DUPLICATE_HOST_HEADERS.getDescription());
                    }
                    for (String hostValue : hostValues)
                    {
                        if (StringUtil.isBlank(hostValue) && !allows(httpCompliance, HttpCompliance.Violation.UNSAFE_HOST_HEADER, listener))
                        {
                            throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, HttpCompliance.Violation.UNSAFE_HOST_HEADER.getDescription());
                        }
                    }
                    seenHostHeader = true;
                }
            }
        }
    }
}
