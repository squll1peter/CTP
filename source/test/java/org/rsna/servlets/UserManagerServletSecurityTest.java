package org.rsna.servlets;

import org.junit.Test;
import org.rsna.server.User;
import org.rsna.server.UsersXmlFileImpl;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class UserManagerServletSecurityTest {

    @Test
    public void csrfTokenIsRenderedFromRequestLocalValue() throws Exception {
        UserManagerServlet servlet = new UserManagerServlet(new File("."), "users");
        UsersXmlFileImpl users = mock(UsersXmlFileImpl.class);
        User user = new User("alice", "hash");
        user.addRole("admin");

        when(users.getUsernames()).thenReturn(new String[] { "alice" });
        when(users.getRoleNames()).thenReturn(new String[] { "admin" });
        when(users.getUser("alice")).thenReturn(user);

        Method getPage = UserManagerServlet.class.getDeclaredMethod(
                "getPage", UsersXmlFileImpl.class, String.class, String.class);
        getPage.setAccessible(true);

        String first = (String)getPage.invoke(servlet, users, "/", "token-one");
        String second = (String)getPage.invoke(servlet, users, "/", "token-two");

        assertTrue(first.contains("value=\"token-one\""));
        assertFalse(first.contains("token-two"));
        assertTrue(second.contains("value=\"token-two\""));
        assertFalse(second.contains("token-one"));
    }
}
