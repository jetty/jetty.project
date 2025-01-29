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

package org.eclipse.jetty.deploy.scan;

import java.util.Comparator;

/**
 * <p>The List of {@link DeployAction} sort.</p>
 *
 * <ul>
 *     <li>{@link DeployAction#getType()} is sorted by all {@link DeployAction.Type#REMOVE}
 *         actions first, followed by all {@link DeployAction.Type#ADD} actions.</li>
 *     <li>{@link DeployAction.Type#REMOVE} type are in descending alphabetically order.</li>
 *     <li>{@link DeployAction.Type#ADD} type are in ascending alphabetically order.</li>
 * </ul>>
 */
public class DeployActionComparator implements Comparator<DeployAction>
{
    private final Comparator<DeployAction> typeComparator;
    private final Comparator<DeployAction> basenameComparator;

    public DeployActionComparator()
    {
        typeComparator = Comparator.comparing(DeployAction::getType);
        basenameComparator = Comparator.comparing(DeployAction::getName);
    }

    @Override
    public int compare(DeployAction o1, DeployAction o2)
    {
        int diff = typeComparator.compare(o1, o2);
        if (diff != 0)
            return diff;
        return switch (o1.getType())
        {
            case REMOVE ->
            {
                yield basenameComparator.compare(o2, o1);
            }
            case ADD ->
            {
                yield basenameComparator.compare(o1, o2);
            }
        };
    }
}
