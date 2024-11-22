package com.example.inventix.exception;

public class OrderItemNotFoundException extends RuntimeException{
    public OrderItemNotFoundException (String message){
        super(message);
    }
}
