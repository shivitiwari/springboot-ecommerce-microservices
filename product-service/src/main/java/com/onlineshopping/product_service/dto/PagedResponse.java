package com.onlineshopping.product_service.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Builder
public class PagedResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
