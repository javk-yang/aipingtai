package com.agentforge.auth.impl.mapper;

import com.agentforge.auth.impl.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper —— 继承 BaseMapper 获得全部 CRUD
 *
 * 设计决策:
 *
 * 1. 为什么接口里一个方法都不写?
 *    BaseMapper 自带: selectById / selectOne / insert / updateById / deleteById
 *    + LambdaQueryWrapper 条件构造器。
 *    90% 的查询用 BaseMapper + Wrapper 就能表达, 不需要写 XML。
 *    等到需要复杂 SQL(比如 P6 的消息分页聚合), 再在这个接口里加 @Select 注解方法或 XML。
 *
 * 2. 为什么继承 BaseMapper<SysUser> 而不是 BaseMapper<SysUserVO>?
 *    泛型必须是实体类。VO 是查询结果映射, 不是表结构映射,
 *    复杂查询返回 VO 时用 @Select 方法 + 独立 VO 类。
 *
 * 3. 为什么不用 @MapperScan 批量扫而是单个 @Mapper?
 *    Bootstrap 启动类已经有 @MapperScan("com.agentforge.**.mapper") 了,
 *    这里加 @Mapper 是双保险(显式声明, 部分 IDE/测试场景不依赖启动类也能注入)。
 *    两者不冲突, MyBatis 会去重。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
