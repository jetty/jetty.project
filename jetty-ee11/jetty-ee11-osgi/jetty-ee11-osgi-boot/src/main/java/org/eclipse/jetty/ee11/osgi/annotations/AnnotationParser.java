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

package org.eclipse.jetty.ee11.osgi.annotations;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.osgi.BundleIndex;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of {@link org.eclipse.jetty.annotations.AnnotationParser} to parse
 * classes inside OSGi bundles.
 */
public class AnnotationParser extends org.eclipse.jetty.ee.osgi.annotations.AnnotationParser
{
    public AnnotationParser()
    {
    }

    public AnnotationParser(int platform)
    {
        super(platform);
    }
}
