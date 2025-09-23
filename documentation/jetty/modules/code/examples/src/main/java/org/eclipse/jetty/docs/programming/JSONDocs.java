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

package org.eclipse.jetty.docs.programming;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.ajax.AsyncJSON;
import org.eclipse.jetty.util.ajax.JSON;

@SuppressWarnings("unused")
public class JSONDocs
{
    public void sync()
    {
        // tag::sync[]
        // Full JSON document.
        String json = """
            {
              "field": "value",
              "count": 42,
              "array": ["one", "two"],
              "object": {
                "size": 13
              }
            }
            """;
        JSON parser = new JSON();
        Object object = parser.parse(new JSON.StringSource(json));
        // The object can be cast to a Map, given the JSON document structure.
        Map<String, Object> map = (Map<String, Object>)object;
        // end::sync[]
    }

    public void async()
    {
        // tag::async[]
        AsyncJSON parser = new AsyncJSON.Factory().newAsyncJSON();

        String jsonChunk1 = """
            {
              "field": "value",
              "cou""";
        parser.parse(StandardCharsets.UTF_8.encode(jsonChunk1));

        String jsonChunk2 = """
            nt": 42,
              "array": ["one", "two"],
              "object": {
                "size": 13
              }
            }
            """;
        parser.parse(StandardCharsets.UTF_8.encode(jsonChunk2));

        // When all the JSON chunks are parsed, complete the parser.
        Object object = parser.complete();
        // The object can be cast to a Map, given the JSON document structure.
        Map<String, Object> map = (Map<String, Object>)object;
        // end::async[]
    }

    public void xclass()
    {
        // tag::xclass[]
        String json = """
            {
              "x-class": "com.acme.Person",
              "firstName": "John",
              "lastName": "Doe",
              "age": 42
              "phone": {
                "x-class": "com.acme.PhoneNumber",
                "number": "+1 800 123456"
              },
              "address": {
                "x-class": "com.acme.Address",
                "address": "123 Main Street"
              }
            }
            """;

        // This class depends on the Jetty JSON library
        // by implementing JSON.Convertible.
        class PhoneNumber implements JSON.Convertible
        {
            private Parts parts;

            @Override
            public void fromJSON(Map<String, Object> object)
            {
                String number = (String)object.get("number");
                parts = parsePhoneNumber(number);
            }

            @Override
            public void toJSON(JSON.Output out)
            {
                out.add("x-class", PhoneNumber.class.getName());
                out.add("number", parts.asString());
            }

            public record Parts(int cc, int ndc, int sn)
            {
                public String asString()
                {
                    return "+%d %d %d".formatted(cc, ndc, sn);
                }
            }
            // tag::xclass-helper[]
            private PhoneNumber.Parts parsePhoneNumber(String number)
            {
                return null;
            }
            // end::xclass-helper[]
        }

        // This class is independent of the Jetty JSON library,
        // and relies on a JSON.Convertor configured externally.
        class Address
        {
            private final String address;

            public Address(String address)
            {
                this.address = address;
            }

            public String getAddress()
            {
                return address;
            }
        }

        // This is the convertor for the Address class,
        // configured on the JSON or AsyncJSON.Factory instances.
        class AddressConvertor implements JSON.Convertor
        {
            @Override
            public Object fromJSON(Map<String, Object> object)
            {
                String address = (String)object.get("address");
                return new Address(address);
            }

            @Override
            public void toJSON(Object object, JSON.Output out)
            {
                Address address = (Address)object;
                out.add("x-class", Address.class.getName());
                out.add("address", address.getAddress());
            }
        }

        // This class is independent of the Jetty JSON library
        // but records do not need external configuration for
        // conversion to/from the JSON format.
        record Person(String firstName, String lastName, int age, PhoneNumber phone)
        {
        }

        JSON parser = new JSON();
        // Configure the convertor for the Address class.
        parser.addConvertor(Address.class, new AddressConvertor());

        Person person = (Person)parser.parse(new JSON.StringSource(json));
        // end::xclass[]
    }

    public void simpleGenerate()
    {
        // tag::simpleGenerate[]
        JSON generator = new JSON();

        Map<String, Object> object = new HashMap<>();
        object.put("user", "John Doe");
        object.put("score", 1234);
        object.put("friends", List.of("Mary Major", "Richard Roe"));

        String json = generator.toJSON(object);

        // The object above is converted to this JSON document.
        json = """
            {
              "user": "John Doe",
              "score": 1234,
              "friends": ["Mary Major", "Richard Roe"]
            }
            """;
        // end::simpleGenerate[]
    }

}
