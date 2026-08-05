package com.autodeploy.repository;

import com.autodeploy.model.BuildRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BuildRecordRepository extends BaseMapper<BuildRecord> {

    @Select("SELECT * FROM build_records WHERE build_time < #{cutoffDate}")
    java.util.List<BuildRecord> findExpiredRecords(@Param("cutoffDate") java.time.LocalDateTime cutoffDate);
}
