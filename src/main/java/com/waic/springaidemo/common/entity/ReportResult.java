package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * PipelineService.generateReport 返回结果，由 Controller 直接序列化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportResult {
    private PeriodEnum period;
    private LocalDate date;
    private String path;
    private String summary;
    private int sourceCount;
    private int categoryCount;
}
