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
     * @param detail the detail of the violation
     * @param complianceListener the listener to notify (can be null)
     * @return true if violation is allowed per the configured compliance.
     */
    public static boolean allowed(ComplianceViolation.Mode compliance,
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
                LOG.atDebug().log("Unable to notify null ComplianceViolation.Listener of {}", event);
            return;
        }

        try
        {
            complianceListener.onComplianceViolation(event);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("Failure while notifying listener {} of event {}", complianceListener, event);
        }
    }

    public static <T extends Throwable> void notifyAndAssert(ComplianceViolation.Mode compliance,
                                                             ComplianceViolation violation,
                                                             ComplianceViolation.Listener complianceListener,
                                                             Function<String, T> error) throws T
    {
        notifyAndAssert(compliance, violation, complianceListener, violation.getDescription(), error);
    }

    public static <T extends Throwable> void notifyAndAssert(ComplianceViolation.Mode compliance,
                                                             ComplianceViolation violation,
                                                             ComplianceViolation.Listener complianceListener,
                                                             String detail,
                                                             Function<String, T> error) throws T
    {
        if (!allowed(compliance, violation, detail, complianceListener))
        {
            throw error.apply(detail);
        }
    }

    /**
     * Assert that the specified Violation is allowed.
     *
     * @param uriCompliance the configured UriCompliance to apply
     * @param uri the HttpURI to check for violations
     * @param listener the listener to report to
     * @param error the function to produce a Throwable if not allowed
     * @param <T> the type of Throwable
     * @throws T Throwable if not allowed
     */
    public static <T extends Throwable> void notifyAndAssert(UriCompliance uriCompliance, HttpURI uri, ComplianceViolation.Listener listener, Function<String, T> error) throws T
    {
        if (!uri.hasViolations())
            return;

        StringBuilder violations = null;
        for (UriCompliance.Violation violation : uri.getViolations())
        {
            boolean allowed = allowed(uriCompliance, violation, violation.getDescription(), listener);

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
    public static void notifyAndAssert(HttpCompliance httpCompliance, MetaData.Request request, ComplianceViolation.Listener listener)
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
                    if (seenContentLength)
                        notifyAndAssert(httpCompliance, HttpCompliance.Violation.MULTIPLE_CONTENT_LENGTHS, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    String[] lengths = httpField.getValues();
                    if (lengths.length > 1)
                        notifyAndAssert(httpCompliance, HttpCompliance.Violation.MULTIPLE_CONTENT_LENGTHS, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    if (seenTransferEncoding)
                        notifyAndAssert(httpCompliance, HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    seenContentLength = true;
                }
                case TRANSFER_ENCODING ->
                {
                    if (seenContentLength)
                        notifyAndAssert(httpCompliance, HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    seenTransferEncoding = true;
                }
                case HOST ->
                {
                    if (seenHostHeader)
                        notifyAndAssert(httpCompliance, HttpCompliance.Violation.DUPLICATE_HOST_HEADERS, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    String[] hostValues = httpField.getValues();
                    if (hostValues.length > 1)
                        notifyAndAssert(httpCompliance, HttpCompliance.Violation.DUPLICATE_HOST_HEADERS, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    for (String hostValue : hostValues)
                    {
                        if (StringUtil.isBlank(hostValue))
                            notifyAndAssert(httpCompliance, HttpCompliance.Violation.UNSAFE_HOST_HEADER, listener, (msg) -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                    }
                    seenHostHeader = true;
                }
            }
        }
    }
}
