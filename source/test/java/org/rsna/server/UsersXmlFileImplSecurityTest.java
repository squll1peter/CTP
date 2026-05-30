package org.rsna.server;

import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.util.DigestUtil;
import org.rsna.util.FileUtil;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class UsersXmlFileImplSecurityTest {

    private final String originalUsersFileName = UsersXmlFileImpl.usersFileName;

    @After
    public void tearDown() {
        UsersXmlFileImpl.usersFileName = originalUsersFileName;
    }

    @Test
    public void legacyMd5UserMigratesToPbkdf2OnSuccessfulLogin() throws Exception {
        File f = File.createTempFile("users", ".xml");
        String md5 = DigestUtil.hash("secret");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<users mode=\"digest\" bootstrapLocalOnly=\"false\">"
                + "<user username=\"alice\" password=\"" + md5 + "\"><role>admin</role></user>"
                + "</users>";
        FileUtil.setText(f, xml);
        UsersXmlFileImpl.usersFileName = f.getAbsolutePath();

        UsersXmlFileImpl users = new UsersXmlFileImpl(null);
        HttpRequest req = Mockito.mock(HttpRequest.class);
        when(req.isFromLocalHost()).thenReturn(true);

        User user = users.authenticate("alice", "secret", req);

        assertNotNull(user);
        assertTrue(users.getUser("alice").getPassword().startsWith(UsersXmlFileImpl.PBKDF2_PREFIX));
    }

    @Test
    public void bootstrapLocalOnlyRejectsRemoteDefaultLogin() throws Exception {
        File f = File.createTempFile("users-missing", ".xml");
        // force constructor into missing-file bootstrap mode
        f.delete();
        UsersXmlFileImpl.usersFileName = f.getAbsolutePath();

        UsersXmlFileImpl users = new UsersXmlFileImpl(null);

        HttpRequest remote = Mockito.mock(HttpRequest.class);
        when(remote.isFromLocalHost()).thenReturn(false);
        HttpRequest local = Mockito.mock(HttpRequest.class);
        when(local.isFromLocalHost()).thenReturn(true);

        assertNull(users.authenticate("admin", "password", remote));
        assertNotNull(users.authenticate("admin", "password", local));
    }
}
