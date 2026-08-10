package com.autodeploy.repository;

import com.autodeploy.model.ShiroSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShiroSessionRepository extends BaseMapper<ShiroSession> {

  @Delete("DELETE FROM shiro_session WHERE expire_at < #{cutoff}")
  int deleteExpired(LocalDateTime cutoff);
}
