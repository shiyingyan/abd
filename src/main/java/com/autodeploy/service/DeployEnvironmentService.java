package com.autodeploy.service;

import com.autodeploy.model.DeployEnvironment;
import com.autodeploy.repository.DeployEnvironmentRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DeployEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(DeployEnvironmentService.class);

    @Autowired
    private DeployEnvironmentRepository environmentRepository;

    public List<DeployEnvironment> listAll() {
        return environmentRepository.selectList(
                new QueryWrapper<DeployEnvironment>().orderByDesc("created_at"));
    }

    public DeployEnvironment getById(Long id) {
        return environmentRepository.selectById(id);
    }

    public DeployEnvironment getByName(String name) {
        return environmentRepository.selectOne(
                new QueryWrapper<DeployEnvironment>().eq("name", name));
    }

    @Transactional
    public String save(DeployEnvironment env) {
        if (env.getName() == null || env.getName().trim().isEmpty()) {
            return "环境名称不能为空";
        }
        DeployEnvironment existing = getByName(env.getName());
        if (existing != null && !existing.getId().equals(env.getId())) {
            return "环境名称 '" + env.getName() + "' 已存在";
        }
        if (env.getId() == null) {
            env.setCreatedAt(LocalDateTime.now());
            environmentRepository.insert(env);
            log.info("Created deploy environment: {}", env.getName());
        } else {
            environmentRepository.updateById(env);
            log.info("Updated deploy environment: {}", env.getName());
        }
        return null;
    }

    @Transactional
    public void delete(Long id) {
        environmentRepository.deleteById(id);
        log.info("Deleted deploy environment id={}", id);
    }
}
