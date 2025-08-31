package com.example.mafiagame.global.dto;

public record CommonResponse<T>(
        boolean success,
        T data,
        String message
) {
    public static <T> CommonResponse<T> success(T data, String message) {
        return new CommonResponse<>(true, data, message);
    }

    public static <T> CommonResponse<T> failure(String message){
        return new CommonResponse<T>(false, null, message);
    }
}
