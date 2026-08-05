package com.autodeploy.repository;

import com.autodeploy.model.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserRepository extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);
}
