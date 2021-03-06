// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.storage.windows;

import com.microsoft.alm.secret.Credential;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CredManagerBackedCredentialStoreTest {

    private CredManagerBackedCredentialStore underTest;
    private final String username = "myusername";
    private final String password = "mypassword";

    @Before
    public void setup() throws Exception {
        underTest = new CredManagerBackedCredentialStore();
    }

    //low value basic tests that should auto run
    @Test
    public void testCreate() throws Exception {
        Credential credential= underTest.create(username, password);

        assertEquals("Username not correct", username, credential.Username);
        assertEquals("Password not correct", password, credential.Password);
    }

    @Test
    public void testGetUsername() throws Exception {
        Credential credential= new Credential(username, password);

        assertEquals("Username is not correct", username, underTest.getUsername(credential));
    }

    @Test
    public void testGetCredentialBlob() throws Exception {
        Credential credential= new Credential(username, password);

        assertEquals("CredentialBlob is not correct", password,
                underTest.getCredentialBlob(credential));
    }
}
