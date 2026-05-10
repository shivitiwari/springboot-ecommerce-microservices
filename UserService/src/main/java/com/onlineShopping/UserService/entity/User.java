package com.onlineShopping.UserService.entity;

import jakarta.persistence.*;
import lombok.*;

@Data
@Getter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @NonNull
    @Column(name = "user_name")
    private String username;
    @NonNull
    @Column(unique = true)
    private String email;
    @NonNull
    private String password;
    private String role;
}
