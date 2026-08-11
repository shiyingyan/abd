package com.autodeploy.repository;

import com.autodeploy.model.ShiroSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShiroSessionRepository extends BaseMapper<ShiroSession> {}
