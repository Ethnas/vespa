// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.net;

import ai.vespa.validation.PatternedStringWrapper;
import ai.vespa.validation.StringWrapper;

import static ai.vespa.validation.Validation.requireInRange;
import static ai.vespa.validation.Validation.requireMatch;

/**
 * A valid hostname, matching {@link DomainName#domainNamePattern}, and no more than 64 characters in length.
 *
 * @author jonmv
 */
public class Hostname extends PatternedStringWrapper<Hostname> {

    private Hostname(String value) {
        super(value, DomainName.domainNamePattern, "hostname");
        requireInRange(value.length(), "hostname length", 1, 64);
    }

    public static Hostname of(String value) {
        return new Hostname(value);
    }

}
