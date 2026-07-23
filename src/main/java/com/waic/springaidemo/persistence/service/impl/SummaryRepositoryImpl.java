package com.waic.springaidemo.persistence.service.impl;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.waic.springaidemo.common.entity.SummaryResult;
import com.waic.springaidemo.common.entity.SummaryCoordinate;
import com.waic.springaidemo.persistence.utils.FilePathUtil;
import com.waic.springaidemo.persistence.service.SummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 聚合树（summary.json）持久化实现
 * @author 10542
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryRepositoryImpl implements SummaryRepository {

    private final ObjectMapper objectMapper;

    @Override
    public boolean existsSummary(SummaryCoordinate coordinate) {
        return Files.exists(FilePathUtil.getSummaryPath(coordinate));
    }

    @Override
    public SummaryResult loadSummary(SummaryCoordinate coordinate) throws IOException {
        Path filePath = FilePathUtil.getSummaryPath(coordinate);
        if (!Files.exists(filePath)) {
            return null;
        }
        return objectMapper.readValue(Files.readString(filePath, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    @Override
    public void saveSummary(SummaryCoordinate coordinate, SummaryResult result) throws IOException {
        Path filePath = FilePathUtil.getSummaryPath(coordinate);
        Files.createDirectories(filePath.getParent());
        // path 由 coordinate 派生（NodeSummary.path()），无需在节点内持久化
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Path temp = Files.createTempFile(filePath.getParent(), ".summary-", ".tmp");
        try {
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveErr) {
                // 部分文件系统不支持原子 move 跨场景，退化为普通覆盖
                Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        log.info("Saved summary to {}", filePath);
    }

    @Override
    public void copySummary(SummaryCoordinate src, SummaryCoordinate dst) throws IOException {
        SummaryResult node = loadSummary(src);
        if (node == null) {
            throw new IllegalStateException("source summary not found: " + src);
        }
        // level/path 由 coordinate 派生：直接把坐标换成目标层即可（D10 复制场景父/子仅层级不同）
        node.setCoordinate(dst);
        saveSummary(dst, node);
        log.info("Copied summary {} -> {}", src, dst);
    }
}
