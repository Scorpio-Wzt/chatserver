package com.zzw.chatserver.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * 关键参数格式校验工具类
 * 验证用户ID、房间ID等核心参数的格式合法性
 */
public class ValidationUtil {

    /**
     * MongoDB ObjectId 正则（24位十六进制字符）
     * 用于校验用户ID、群组ID等ObjectId类型参数
     */
    private static final String OBJECT_ID_REGEX = "^[0-9a-fA-F]{24}$";

    /**
     * 房间ID正则（支持单聊房间ID和群聊房间ID）
     * - 群聊房间ID：直接使用群组ObjectId（24位十六进制）
     * - 单聊房间ID：格式为"用户ID-用户ID"（两个ObjectId用短横线连接）
     */
    private static final String ROOM_ID_REGEX = "^[0-9a-fA-F]{24}(-[0-9a-fA-F]{24})?$";

    /**
     * 校验MongoDB ObjectId格式（用户ID、群组ID等）
     * @param objectId 待校验的ID
     * @return 格式合法返回true，否则返回false
     */
    public static boolean isValidObjectId(String objectId) {
        if (StringUtils.isBlank(objectId)) {
            return false;
        }
        return objectId.matches(OBJECT_ID_REGEX);
    }

    /**
     * 校验房间ID格式（单聊/群聊房间）
     * @param roomId 待校验的房间ID
     * @return 格式合法返回true，否则返回false
     */
    public static boolean isValidRoomId(String roomId) {
        if (StringUtils.isBlank(roomId)) {
            return false;
        }
        return roomId.matches(ROOM_ID_REGEX);
    }

    /**
     * 校验单聊房间ID（必须包含两个合法ObjectId，用短横线连接）
     * @param singleRoomId 单聊房间ID
     * @return 格式合法返回true，否则返回false
     */
    public static boolean isValidSingleRoomId(String singleRoomId) {
        if (StringUtils.isBlank(singleRoomId)) {
            return false;
        }
        String[] parts = singleRoomId.split("-");
        if (parts.length != 2) {
            return false;
        }
        // 两个部分都必须是合法的ObjectId
        return isValidObjectId(parts[0]) && isValidObjectId(parts[1]);
    }
}