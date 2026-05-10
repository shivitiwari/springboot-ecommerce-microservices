package com.onlineShopping.UserService.dto;

import jakarta.persistence.Column;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UpdateUser {
    private String username;
    private String email;
    private String password;
    private String role;
}