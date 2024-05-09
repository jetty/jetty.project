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

package org.eclipse.jetty.ee9.nested;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.MultiPartCompliance;

/**
 * Generic `multipart/*` interface
 */
class MultiPart
{
    /**
     * Create a new parser for `multipart/form-data` content.
     *
     * @param multiPartCompliance the compliance mode
     * @param inputStream the input stream
     * @param contentType the Request {@code Content-Type}
     * @param config the servlet Multipart configuration
     * @param contextTmpDir the temporary directory to use (if config has it unspecified)
     * @param maxParts the maximum number of parts allowed
     * @return the parser for `multipart/form-data` content
     */
    public static MultiPart.Parser newFormDataParser(
                                                     MultiPartCompliance multiPartCompliance,
                                                     InputStream inputStream,
                                                     String contentType,
                                                     MultipartConfigElement config,
                                                     File contextTmpDir,
                                                     int maxParts)
    {
        if (multiPartCompliance == MultiPartCompliance.RFC7578)
            return new MultiPartFormInputStream(inputStream, contentType, config, contextTmpDir, maxParts);
        else
            return new MultiPartInputStreamLegacyParser(
                multiPartCompliance, inputStream, contentType, config, contextTmpDir, maxParts);
    }

    public interface Parser
    {
        void deleteParts();

        Part getPart(String name) throws IOException;

        Collection<Part> getParts() throws IOException;

        List<ComplianceViolation.Event> getNonComplianceWarnings();
    }
}
