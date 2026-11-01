/*
 Navicat Premium Data Transfer

 Source Server         : local
 Source Server Type    : MySQL
 Source Server Version : 50622
 Source Host           : localhost:3306
 Source Schema         : sportcourt2

 Target Server Type    : MySQL
 Target Server Version : 50622
 File Encoding         : 65001

 Date: 02/03/2022 23:12:54
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_post
-- ----------------------------
DROP TABLE IF EXISTS `tb_post`;
CREATE TABLE `tb_post`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `venue_id` bigint(20) NOT NULL COMMENT '场馆id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '约球的照片，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '约球的文字描述',
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_post
INSERT INTO `tb_post` VALUES
(1, 1, 1, '周三晚羽毛球约球，徐汇滨江缺两位', '/imgs/posts/sport-1.jpg', '周三 19:00 在徐汇滨江体育公园打双打，水平 3.0 左右，欢迎守时的球友报名。', 12, 3, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(2, 2, 2, '浦东网球新手局招募', '/imgs/posts/sport-2.jpg', '陆家嘴附近周末网球练习，提供两筒球，约 2 小时，想一起练发球的朋友留言。', 8, 2, '2026-10-03 09:30:00', '2026-10-03 09:30:00'),
(3, 3, 1, '静安篮球半场战报', '/imgs/posts/sport-3.jpg', '昨晚三打三打得很过瘾，最后一球反超，感谢每位到场的球友。', 21, 5, '2026-10-05 21:20:00', '2026-10-05 21:20:00');

-- ----------------------------
-- Table structure for tb_post_comments
-- ----------------------------
DROP TABLE IF EXISTS `tb_post_comments`;
CREATE TABLE `tb_post_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `post_id` bigint(20) UNSIGNED NOT NULL COMMENT '约球id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '点赞数',
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_post_comments
-- ----------------------------

-- ----------------------------
-- Table structure for tb_follow
-- ----------------------------
DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_follow
-- ----------------------------

-- ----------------------------
-- Table structure for tb_golden_slot_booking_pass
-- ----------------------------
DROP TABLE IF EXISTS `tb_golden_slot_booking_pass`;
CREATE TABLE `tb_golden_slot_booking_pass`  (
  `booking_pass_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的订场券的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT '生效时间',
  `end_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`booking_pass_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '整点抢黄金时段订场券表，与订场券是一对一关系' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_golden_slot_booking_pass
-- ----------------------------
INSERT INTO `tb_golden_slot_booking_pass`
  (`booking_pass_id`, `stock`, `create_time`, `begin_time`, `end_time`, `update_time`)
VALUES
  (2, 120, '2026-10-01 09:00:00', '2026-10-01 09:00:00', '2026-12-31 23:59:59', '2026-10-01 09:00:00');

-- ----------------------------
-- Table structure for tb_venue
-- ----------------------------
DROP TABLE IF EXISTS `tb_venue`;
CREATE TABLE `tb_venue`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '场馆名称',
  `type_id` bigint(20) UNSIGNED NOT NULL COMMENT '场馆类型的id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '场馆图片，多个图片以\',\'隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '区域，例如徐汇滨江',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
  `x` double UNSIGNED NOT NULL COMMENT '经度',
  `y` double UNSIGNED NOT NULL COMMENT '维度',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价，取整数',
  `sold` int(10) UNSIGNED NOT NULL COMMENT '销量',
  `comments` int(10) UNSIGNED NOT NULL COMMENT '评论数量',
  `score` int(2) UNSIGNED NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_venue
INSERT INTO `tb_venue` VALUES
(1, '徐汇滨江体育公园羽毛球馆', 1, '/imgs/venues/xuhui-badminton.jpg', '徐汇滨江', '上海市徐汇区龙兰路 100 号', 121.4632, 31.1746, 88, 1260, 328, 48, '07:00-23:00', '2026-09-01 09:00:00', '2026-10-01 09:00:00'),
(2, '浦东陆家嘴网球中心', 2, '/imgs/venues/lujiazui-tennis.jpg', '陆家嘴', '上海市浦东新区浦明路 188 号', 121.5106, 31.2407, 128, 980, 214, 47, '06:30-22:30', '2026-09-01 09:00:00', '2026-10-02 09:00:00'),
(3, '静安体育馆篮球场', 3, '/imgs/venues/jingan-basketball.jpg', '静安寺', '上海市静安区西康路 99 号', 121.4433, 31.2291, 100, 1540, 406, 49, '08:00-23:00', '2026-09-01 09:00:00', '2026-10-03 09:00:00'),
(4, '杨浦滨江五人制足球场', 4, '/imgs/venues/yangpu-football.jpg', '杨浦滨江', '上海市杨浦区杨树浦路 855 号', 121.5450, 31.2585, 160, 760, 162, 46, '07:00-22:00', '2026-09-01 09:00:00', '2026-10-04 09:00:00');

-- ----------------------------
-- Table structure for tb_sport_type
-- ----------------------------
DROP TABLE IF EXISTS `tb_sport_type`;
CREATE TABLE `tb_sport_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_sport_type
INSERT INTO `tb_sport_type` VALUES
(1, '羽毛球', '/types/badminton.png', 1, '2026-09-01 09:00:00', '2026-09-01 09:00:00'),
(2, '网球', '/types/tennis.png', 2, '2026-09-01 09:00:00', '2026-09-01 09:00:00'),
(3, '篮球', '/types/basketball.png', 3, '2026-09-01 09:00:00', '2026-09-01 09:00:00'),
(4, '足球', '/types/football.png', 4, '2026-09-01 09:00:00', '2026-09-01 09:00:00'),
(5, '乒乓球', '/types/table-tennis.png', 5, '2026-09-01 09:00:00', '2026-09-01 09:00:00');

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1010 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
INSERT INTO `tb_user` VALUES
(1, '13800001001', '', '徐汇羽球手', '/imgs/icons/user-1.jpg', '2026-09-01 09:00:00', '2026-10-01 09:00:00'),
(2, '13800001002', '', '浦东网球搭子', '/imgs/icons/user-2.jpg', '2026-09-01 09:00:00', '2026-10-01 09:00:00'),
(3, '13800001003', '', '静安篮球队长', '/imgs/icons/user-3.jpg', '2026-09-01 09:00:00', '2026-10-01 09:00:00');

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
  `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '关注的人的数量',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------

-- ----------------------------
-- Table structure for tb_booking_pass
-- ----------------------------
DROP TABLE IF EXISTS `tb_booking_pass`;
CREATE TABLE `tb_booking_pass`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `venue_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '场馆id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订场券标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,整点抢黄金时段券',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_booking_pass
INSERT INTO `tb_booking_pass` VALUES
(1, 1, '工作日晚间羽毛球场 2 小时', '徐汇滨江体育公园', '限周一至周五 18:00 前预约，入场请出示订单。', 6800, 8000, 0, 1, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
(2, 2, '周末网球黄金时段抢场', '陆家嘴网球中心', '整点开放周六 19:00 至 21:00 场地，实名入场。', 9800, 12000, 1, 1, '2026-10-01 09:00:00', '2026-10-01 09:00:00');

-- ----------------------------
-- Table structure for tb_booking_pass_order
-- ----------------------------
DROP TABLE IF EXISTS `tb_booking_pass_order`;
CREATE TABLE `tb_booking_pass_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `booking_pass_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的订场券id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_booking_pass_order
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
