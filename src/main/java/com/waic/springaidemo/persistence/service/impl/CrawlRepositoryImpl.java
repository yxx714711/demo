package com.waic.springaidemo.persistence.service.impl;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.persistence.utils.FilePathUtil;
import com.waic.springaidemo.persistence.service.CrawlRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 原始抓取结果持久化实现（hotitems.json + markdown 正文）
 * @author 10542
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlRepositoryImpl implements CrawlRepository {

    private final ObjectMapper objectMapper;

    @Override
    public void saveItems(CrawlResult result) throws IOException {
        CrawlCoordinate coordinate = result.getCoordinate();
        Path filePath = FilePathUtil.getHotItemsJsonPath(coordinate);
        Files.createDirectories(filePath.getParent());
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
        log.info("Saved fetch result to {}", filePath);
    }

    @Override
    public void updateItems(CrawlResult result) throws IOException {
        CrawlCoordinate coordinate = result.getCoordinate();
        Path filePath = FilePathUtil.getHotItemsJsonPath(coordinate);
        if (!Files.exists(filePath)) {
            log.warn("Fetch result file not found: {}", filePath);
            return;
        }
        CrawlResult existing = objectMapper.readValue(
                Files.readString(filePath, StandardCharsets.UTF_8),
                new TypeReference<>() {});
        Map<String, String> contentPathMap = result.getItems().stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(HotItem::getId, HotItem::getContentPath, (a, b) -> b));
        for (HotItem item : existing.getItems()) {
            String newContentPath = contentPathMap.get(item.getId());
            if (newContentPath != null) {
                item.setContentPath(newContentPath);
            }
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(existing);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
        log.info("Updated contentPath for {} items in {}", contentPathMap.size(), filePath);
    }

    @Override
    public Optional<CrawlResult> loadItem(CrawlCoordinate coordinate) {
        Path filePath = FilePathUtil.getHotItemsJsonPath(coordinate);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            CrawlResult result = objectMapper.readValue(
                    Files.readString(filePath, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            return Optional.ofNullable(result);
        } catch (IOException e) {
            log.warn("Failed to load hotitems from {}, treat as absent", filePath, e);
            return Optional.empty();
        }
    }

    @Override
    public List<CrawlResult> loadItems(CrawlCoordinate coordinate) throws IOException {
        Path baseDir = FilePathUtil.getCrawlDir(coordinate);
        return walkHotItems(baseDir);
    }

    @Override
    public List<CrawlResult> loadItems(PeriodEnum period, LocalDate date) throws IOException {
        List<CrawlResult> results = new ArrayList<>();
        for (DataSourceEnum source : DataSourceEnum.values()) {
            results.addAll(loadItems(new CrawlCoordinate(period, date, source, null, null)));
        }
        return results;
    }

    private List<CrawlResult> walkHotItems(Path baseDir) throws IOException {
        List<CrawlResult> results = new ArrayList<>();
        if (!Files.exists(baseDir)) {
            return results;
        }
        try (Stream<Path> walk = Files.walk(baseDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(FilePathUtil.HOTITEMS_FILE))
                    .toList();
            for (Path file : files) {
                try {
                    CrawlResult result = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                            new TypeReference<>() {});
                    results.add(result);
                } catch (IOException e) {
                    log.error("Failed to load fetch result from {}", file, e);
                }
            }
        }
        return results;
    }

    @Override
    public void saveContent(CrawlCoordinate coordinate, HotItem item, String text) throws IOException {
        if (text == null || text.isBlank()) {
            item.setContentPath(HotItem.CONTENT_NOT_FOUND);
            return;
        }
        Path contentFilePath = FilePathUtil.getContentFilePath(coordinate, item.getId());
        Files.createDirectories(contentFilePath.getParent());
        Files.writeString(contentFilePath, text, StandardCharsets.UTF_8);
        item.setContentPath(contentFilePath.toString().replace("\\", "/"));
        log.info("Saved content for {} to {}", item.getTitle(), item.getContentPath());
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
}
