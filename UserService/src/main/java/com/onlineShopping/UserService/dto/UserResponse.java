package com.onlineShopping.UserService.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserResponse {
    private long id;
    private String username;
    private String email;
    private String role;

    // Alias for Order Service compatibility
//    public String getName() {
//        return username;
//    }
}
