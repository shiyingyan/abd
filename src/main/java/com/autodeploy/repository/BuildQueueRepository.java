package com.autodeploy.repository;

import com.autodeploy.model.BuildQueueTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuildQueueRepository extends BaseMapper<BuildQueueTask> {}
