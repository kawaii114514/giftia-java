package com.giftia.model;

public record ModelMessage(String role, String content, String imageData) {
    public ModelMessage(String role, String content) { this(role, content, null); }
}
