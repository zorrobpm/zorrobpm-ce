package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PagedDataDTO<T> {
    private Integer pageIndex;
    private Integer pageSize;
    private Long totalElements;
    private List<T> data;
}
