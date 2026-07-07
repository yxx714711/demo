package com.waic.springaidemo.persistence.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.utils.FilePathUtils;
import com.waic.springaidemo.persistence.service.PersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 持久化服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistenceServiceImpl implements PersistenceService {

    private final ObjectMapper objectMapper;

    @Override
    public void save(FetchResult result) throws IOException {
        Path filePath = FilePathUtils.getHotItemsFilePath(result.getSource(), result.getPeriod(),
                result.getDate(), result.getCategory(), result.getLanguage());
        Files.createDirectories(filePath.getParent());
        String json = objectMapper.writeValueAsString(result);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
        log.info("Saved fetch result to {}", filePath);
    }

    @Override
    public List<FetchResult> loadByDate(DataSourceEnum source, PeriodEnum period, LocalDate date) throws IOException {
        List<FetchResult> results = new ArrayList<>();
        Path baseDir = Paths.get("data", source.getCode(), period.getCode(), date.toString());
        if (!Files.exists(baseDir)) {
            return results;
        }

        try (Stream<Path> walk = Files.walk(baseDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("hotitems.json"))
                    .collect(Collectors.toList());
            for (Path file : files) {
                try {
                    FetchResult result = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                            new TypeReference<FetchResult>() {
                            });
                    results.add(result);
                } catch (IOException e) {
                    log.error("Failed to load fetch result from {}", file, e);
                }
            }
        }
        return results;
    }

    @Override
    public String loadContent(String contentPath) throws IOException {
        if (contentPath == null || contentPath.isBlank()) {
            return "";
        }
        Path path = Paths.get(contentPath);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override
    public String saveReport(String reportType, LocalDate date, String content) throws IOException {
        Path filePath = FilePathUtils.getReportFilePath(reportType, date);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
        log.info("Saved report to {}", filePath);
        return filePath.toString().replace("\\", "/");
    }
}
