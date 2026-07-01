package net.talaatharb.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {

    @Test
    void shouldInitializeAndToggleActiveState() {
        UserService service = new UserService("alice", "alice@example.com");

        assertEquals("alice", service.getUsername());
        assertEquals("alice@example.com", service.getEmail());
        assertTrue(service.isActive());

        service.deactivate();
        assertFalse(service.isActive());

        service.activate();
        assertTrue(service.isActive());
    }

    @Test
    void shouldValidateEmailAddress() {
        UserService service = new UserService("bob", "bob@example.com");
        assertTrue(service.validateEmail());

        service.setEmail("invalid-email");
        assertFalse(service.validateEmail());

        service.setEmail(null);
        assertFalse(service.validateEmail());
    }
}
