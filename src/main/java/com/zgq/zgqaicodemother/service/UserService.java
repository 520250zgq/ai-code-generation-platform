package com.zgq.zgqaicodemother.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.zgq.zgqaicodemother.model.dto.user.UserQueryRequest;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.vo.LoginUserVO;
import com.zgq.zgqaicodemother.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 用户服务层：负责注册、登录态、密码加密、查询条件构造和视图脱敏。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
public interface UserService extends IService<User> {

    /**
     * 注册新用户，校验账号密码并保存加密后的密码。
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 对用户密码做加盐 MD5，注册和登录共用同一规则。
     *
     * @param userPassword 明文密码
     * @return 加密后的密码
     */
    String getEncryptPassword(String userPassword);

    /**
     * 获取脱敏的已登录用户信息，供登录接口返回。
     *
     * @param user 用户实体，允许为 null
     * @return 不含密码等敏感字段的登录视图；用户为空时返回 null
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 校验账号密码，成功后把用户写入 Session。
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request      保存登录态的请求对象
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 从 Session 读取当前登录用户，并回查数据库确认账号仍有效。
     *
     * @param request 携带 Session 的请求
     * @return 当前登录用户实体
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 移除 Session 中的登录态。
     *
     * @param request 携带 Session 的请求
     * @return 是否注销成功
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 把用户实体转换为脱敏视图，避免密码等字段返回前端。
     *
     * @param user 用户实体，允许为 null
     * @return 脱敏用户视图；用户为空时返回 null
     */
    UserVO getUserVO(User user);

    /**
     * 批量转换用户视图，列表接口用它避免逐条重复脱敏。
     *
     * @param userList 用户实体列表
     * @return 保持原顺序的脱敏用户视图列表
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 根据管理端查询条件构造 QueryWrapper，支持账号、昵称、简介和角色筛选。
     *
     * @param userQueryRequest 查询及排序条件
     * @return 可复用的查询条件
     */
    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);
}
