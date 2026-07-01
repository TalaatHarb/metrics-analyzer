package net.talaatharb.sample;

/**
 * Example: Well-designed UserService (HIGH cohesion, LOW coupling)
 */
public class UserService {
    private String username;
    private String email;
    private boolean isActive;

    public UserService(String username, String email) {
        this.username = username;
        this.email = email;
        this.isActive = true;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isActive() {
        return isActive;
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void activate() {
        this.isActive = true;
    }

    public void printUserInfo() {
        System.out.println("User: " + username + ", Email: " + email + ", Active: " + isActive);
    }

    public boolean validateEmail() {
        return email != null && email.contains("@");
    }
}
