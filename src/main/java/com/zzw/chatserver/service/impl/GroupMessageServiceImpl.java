package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.dao.GroupMessageDao;
import com.zzw.chatserver.pojo.GroupMessage;
import com.zzw.chatserver.pojo.vo.GroupHistoryResultVo;
import com.zzw.chatserver.pojo.vo.GroupMessageResultVo;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.service.GroupMessageService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 群聊消息服务实现类
 * 实现GroupMessageService接口定义的群聊消息操作，依赖DAO层和MongoTemplate完成数据交互
 */
@Service
public class GroupMessageServiceImpl implements GroupMessageService {

    @Resource
    private GroupMessageDao groupMessageDao;

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 获取群聊历史消息
     * 支持按消息类型、关键词、日期筛选，结合分页返回结果
     */
    @Override
    public GroupHistoryResultVo getGroupHistoryMessages(HistoryMsgRequestVo groupHistoryVo) {
        // 1. 构建基础查询条件：房间ID匹配
        Criteria cri1 = Criteria.where("roomId").is(groupHistoryVo.getRoomId());
        Criteria cri2 = null;

        // 2. 按查询类型筛选（非"all"时按类型+文件名筛选，"all"时按消息内容+文件名筛选）
        if (!"all".equals(groupHistoryVo.getType())) {
            // 非全部类型：匹配消息类型 + 文件名模糊查询（不区分大小写）
            cri1.and("messageType").is(groupHistoryVo.getType())
                    .and("fileRawName").regex(Pattern.compile("^.*" + groupHistoryVo.getQuery() + ".*$", Pattern.CASE_INSENSITIVE));
        } else {
            // 全部类型：模糊匹配消息内容 或 文件名（不区分大小写）
            cri2 = new Criteria().orOperator(
                    Criteria.where("message").regex(Pattern.compile("^.*" + groupHistoryVo.getQuery() + ".*$", Pattern.CASE_INSENSITIVE)),
                    Criteria.where("fileRawName").regex(Pattern.compile("^.*" + groupHistoryVo.getQuery() + ".*$", Pattern.CASE_INSENSITIVE))
            );
        }

        // 3. 按日期筛选（查询指定日期当天的消息）
        if (groupHistoryVo.getDate() != null) {
            Calendar calendar = new GregorianCalendar();
            calendar.setTime(groupHistoryVo.getDate());
            calendar.add(Calendar.DATE, 1); // 计算次日0点，实现“当天消息”筛选
            Date tomorrow = calendar.getTime();
            cri1.and("time").gte(groupHistoryVo.getDate()).lt(tomorrow);
        }

        // 4. 构建最终查询对象
        Query query = new Query();
        if (cri2 != null) {
            // 多条件组合：基础条件 + 类型/关键词条件（且关系）
            query.addCriteria(new Criteria().andOperator(cri1, cri2));
        } else {
            query.addCriteria(cri1);
        }

        // 5. 统计符合条件的总条数
        long count = mongoTemplate.count(query, GroupMessageResultVo.class, "groupmessages");

        // 6. 设置分页参数（页码从0开始）
        query.skip((long) groupHistoryVo.getPageIndex() * groupHistoryVo.getPageSize())
                .limit(groupHistoryVo.getPageSize());

        // 7. 执行查询，获取消息列表
        List<GroupMessageResultVo> messageList = mongoTemplate.find(query, GroupMessageResultVo.class, "groupmessages");

        // 8. 封装并返回结果
        return new GroupHistoryResultVo(messageList, count);
    }

    /**
     * 获取群聊最后一条消息
     * 按消息ID降序查询（MongoDB _id 自增，降序即最新消息），无消息时返回空VO
     */
    @Override
    public GroupMessageResultVo getGroupLastMessage(String roomId) {
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id")); // 按ID降序，取最新一条

        GroupMessageResultVo res = mongoTemplate.findOne(query, GroupMessageResultVo.class, "groupmessages");
        // 无消息时返回空VO，避免后续空指针
        if (res == null) {
            res = new GroupMessageResultVo();
        }
        return res;
    }

    /**
     * 获取群聊最近消息
     * 按ID降序（最新消息在前），结合分页返回指定条数的消息
     */
    @Override
    public List<GroupMessageResultVo> getRecentGroupMessages(String roomId, Integer pageIndex, Integer pageSize) {
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id")) // 最新消息在前
                .skip((long) pageIndex * pageSize) // 分页偏移量
                .limit(pageSize); // 每页条数

        return mongoTemplate.find(query, GroupMessageResultVo.class, "groupmessages");
    }

    /**
     * 保存新的群聊消息
     * 委托DAO层将消息实体持久化到数据库
     */
    @Override
    public void addNewGroupMessage(GroupMessage groupMessage) {
        groupMessageDao.save(groupMessage);
    }
}