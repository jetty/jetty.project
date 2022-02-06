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

document.addEventListener('DOMContentLoaded', () => dynamicTOC());

function dynamicTOC() {
    const content = document.getElementById('content');
    // Bind a click listener to all section titles.
    const sectionTitles = content.querySelectorAll('a.link');
    for (const sectionTitle of sectionTitles) {
        sectionTitle.addEventListener('click', event => collapseThenExpand(hash(event.target)));
    }
    // Bind a click listener to all inline links to documentation sections.
    const inlineLinks = content.querySelectorAll('p > a');
    for (const inlineLink of inlineLinks) {
        const linkHash = inlineLink.hash || '';
        if (linkHash.startsWith('#')) {
            inlineLink.addEventListener('click', event => collapseThenExpand(hash(event.target)));
        }
    }

    // Bind a click listener to all TOC titles.
    const toc = document.getElementById('toc');
    const tocTitles = toc.querySelectorAll('a');
    for (const tocTitle of tocTitles) {
        tocTitle.addEventListener('click', event => collapseThenExpand(hash(event.target)));
    }

    // Add the icons to TOC nodes.
    const nodes = toc.querySelectorAll('li');
    for (const node of nodes) {
        const span = document.createElement('span');
        const css = span.classList;
        if (node.querySelector(':scope > ul')) {
            css.add('toc-toggle');
            // Font-Awesome classes.
            css.add('fa');
            css.add('fa-caret-right');
            span.addEventListener('click', event => toggle(event.target));
        } else {
            css.add('toc-item');
            // The "icon" is the &bull; HTML entity.
            span.appendChild(document.createTextNode('•'));
        }
        node.prepend(span);
    }

    collapseThenExpand(document.location.hash);
}

function hash(element) {
    const a = element.closest('a');
    return a ? a.hash : null;
}

function collapseThenExpand(hash) {
    const toc = document.getElementById('toc');
    if (hash) {
        const current = toc.querySelector('a.toc-current');
        if (current) {
            current.classList.remove('toc-current');
        }
        const anchor = toc.querySelector('a[href="' + hash + '"');
        if (anchor) {
            anchor.classList.add('toc-current');
            collapse(toc);
            expand(anchor.parentNode);
        }
    } else {
        collapse(toc);
    }
}

function collapse(node) {
    const sections = node.querySelectorAll('ul');
    for (const section of sections) {
        const css = section.classList;
        // Always show first levels TOC titles.
        const alwaysShow = css.contains('sectlevel1') || css.contains('sectlevel2');
        if (!alwaysShow) {
            css.add('hidden');
        }
    }
    // Show the collapsed icon.
    const spans = node.querySelectorAll('span.toc-toggle');
    for (const span of spans) {
        const css = span.classList;
        css.remove('fa-caret-down');
        css.add('fa-caret-right');
    }
}

function expand(node) {
    const root = document.getElementById('toc').querySelector('ul');
    // Show the current node and its ancestors.
    let parent = node;
    while (parent !== root) {
        // Show the node.
        parent.classList.remove('hidden');
        // Show the expanded icon.
        const span = parent.querySelector(':scope > span.toc-toggle');
        if (span) {
            const css = span.classList;
            css.remove('fa-caret-right');
            css.add('fa-caret-down');
        }
        parent = parent.parentNode;
    }
    // Show the children.
    const children = node.querySelector(':scope > ul');
    if (children) {
        children.classList.remove('hidden');
    }
}

function toggle(span) {
    const css = span.classList;
    const expanded = css.contains('fa-caret-down');
    if (expanded) {
        collapse(span.parentNode);
    } else {
        expand(span.parentNode);
    }
}
