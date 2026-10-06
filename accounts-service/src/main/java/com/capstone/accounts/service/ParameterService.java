package com.capstone.accounts.service;

import com.capstone.accounts.entity.SystemParameter;
import com.capstone.accounts.repository.SystemParameterRepository;
import com.capstone.accounts.repository.SystemParameterAuditRepository;
import com.capstone.common.dto.SystemParameterDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ParameterService {
    private final SystemParameterRepository repository;
    private final SystemParameterAuditRepository auditRepository;
    private final StringRedisTemplate redisTemplate;

    public List<SystemParameterDTO> getAllParameters() {
        return repository.findByIsActive("Y").stream()
                .map(p -> new SystemParameterDTO(
                        p.getParamKey(), p.getParamDescription(), p.getParamValueNumeric(),
                        p.getParamValueText(), p.getParamVersion()))
                .collect(Collectors.toList());
    }

    @Transactional
    public SystemParameterDTO updateParameter(String key, SystemParameterDTO update, String adminUser, String reason) {
        SystemParameter param = repository.findById(key)
                .orElseThrow(() -> new RuntimeException("Parameter not found: " + key));
        
        // Optimistic version check or just increment
        int newVersion = param.getParamVersion() + 1;
        
        // Audit log
        com.capstone.accounts.entity.SystemParameterAudit audit = com.capstone.accounts.entity.SystemParameterAudit.builder()
                .auditId(UUID.randomUUID().toString())
                .paramKey(key)
                .paramVersion(newVersion)
                .oldValueNumeric(param.getParamValueNumeric())
                .newValueNumeric(update.paramValueNumeric())
                .oldValueText(param.getParamValueText())
                .newValueText(update.paramValueText())
                .changeReason(reason)
                .changedBy(adminUser)
                .changedAt(LocalDateTime.now())
                .build();
        auditRepository.save(audit);

        // Update parameter
        param.setParamValueNumeric(update.paramValueNumeric());
        param.setParamValueText(update.paramValueText());
        param.setParamVersion(newVersion);
        param.setUpdatedBy(adminUser);
        param.setUpdatedAt(LocalDateTime.now());
        repository.save(param);

        // Invalidate caching
        redisTemplate.delete("parameter:" + key);
        
        return new SystemParameterDTO(param.getParamKey(), param.getParamDescription(), 
                param.getParamValueNumeric(), param.getParamValueText(), param.getParamVersion());
    }
}
