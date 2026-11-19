
# 数据库初始化
# @author <a href="https://github.com/liyupi">程序员鱼皮</a>
# @from <a href="https://codefather.cn">编程导航学习圈</a>

-- 创建库
create database if not exists `yu-ai-code-mother`;

-- 切换库
use `yu-ai-code-mother`;

-- 用户表
-- 以下是建表语句
-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
    ) comment '用户' collate = utf8mb4_unicode_ci;

-- 应用表
-- id 由 App 实体配置的雪花算法在插入前生成，因此不使用数据库自增主键。
-- 应用名和创建者由服务端设置；初始化提示词必填，使用 TEXT 保存较长的生成需求。
create table if not exists app
(
    id           bigint                                not null comment 'id' primary key,
    appName      varchar(256)                          not null comment '应用名称',
    cover        varchar(1024)                         null comment '应用封面',
    initPrompt   text                                  not null comment '应用初始化的 prompt',
    codeGenType  varchar(128)                          null comment '代码生成类型（枚举）',
    deployKey    varchar(256)                          null comment '部署标识',
    deployedTime datetime                              null comment '部署时间',
    priority     int          default 0                 not null comment '优先级',
    userId       bigint                                not null comment '创建用户 id',
    -- 插入时默认生成时间；editTime 由业务修改接口主动刷新，updateTime 由数据库随行更新维护。
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    -- 与 App.isDelete 的逻辑删除注解对应：正常删除仅改变标记，保留记录供管理员按条件查询。
    isDelete     tinyint      default 0                 not null comment '是否删除',
    -- userId 支持“我的应用”筛选，priority 支持精选筛选；appName 索引有利于名称精确或前缀查询，
    -- 当前名称筛选使用包含式 LIKE，存在前导通配符时通常不能直接利用该索引定位。
    INDEX idx_userId (userId),
    INDEX idx_appName (appName),
    INDEX idx_priority (priority)
) comment '应用' collate = utf8mb4_unicode_ci;

-- 对话历史表；id 使用 ChatHistory 实体的雪花算法生成。
create table if not exists chat_history
(
    id          bigint                               not null comment 'id' primary key,
    message     longtext                             not null comment '消息内容',
    messageType varchar(32)                          not null comment '消息类型：user/ai',
    appId       bigint                               not null comment '所属应用 id',
    userId      bigint                               not null comment '发起对话的用户 id',
    createTime  datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint     default 0                not null comment '是否删除',
    INDEX idx_appId_createTime (appId, createTime, id),
    INDEX idx_userId_createTime (userId, createTime)
) comment '对话历史' collate = utf8mb4_unicode_ci;
