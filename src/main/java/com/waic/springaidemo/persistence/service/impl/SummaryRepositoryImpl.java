package com.waic.springaidemo.persistence.service.impl;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.waic.springaidemo.common.entity.NodeSummary;
import com.waic.springaidemo.common.entity.SummaryKey;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryRepositoryImpl implements SummaryRepository {

    private final ObjectMapper objectMapper;

    @Override
    public boolean existsSummary(SummaryKey key) {
        return Files.exists(FilePathUtil.getSummaryPath(key));
    }

    @Override
    public NodeSummary loadSummary(SummaryKey key) throws IOException {
        Path filePath = FilePathUtil.getSummaryPath(key);
        if (!Files.exists(filePath)) {
            return null;
        }
        return objectMapper.readValue(Files.readString(filePath, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    @Override
    public void saveSummary(SummaryKey key, NodeSummary summary) throws IOException {
        Path filePath = FilePathUtil.getSummaryPath(key);
        Files.createDirectories(filePath.getParent());
        summary.setPath(filePath.toString().replace("\\", "/"));
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
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
    public void copySummary(SummaryKey src, SummaryKey dst) throws IOException {
        NodeSummary node = loadSummary(src);
        if (node == null) {
            throw new IllegalStateException("source summary not found: " + src);
        }
        node.setLevel(dst.level());
        node.setPath(FilePathUtil.getSummaryPath(dst).toString().replace("\\", "/"));
        saveSummary(dst, node);
        log.info("Copied summary {} -> {}", src, dst);
    }
}
