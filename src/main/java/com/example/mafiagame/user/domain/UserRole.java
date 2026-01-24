package com.example.mafiagame.user.domain;

public enum UserRole {
    USER, ADMIN;

    public String getRoleName() {
        return "ROLE_" + this.name();
    }
}
